using System.Net;
using System.Text;
using AirClip.Discovery;
using AirClip.Discovery.Dns;
using Xunit;

namespace AirClip.Sync.Tests;

public class ServiceNamingTests
{
    [Theory]
    [InlineData("_airclip._tcp.local.", "_airclip._tcp.local")]
    [InlineData("  _airclip._tcp.local.  ", "_airclip._tcp.local")]
    [InlineData(null, "")]
    public void Names_normalise_away_the_trailing_dot(string? input, string expected)
    {
        Assert.Equal(expected, DnsName.Normalise(input));
    }

    [Fact]
    public void Names_compare_without_caring_about_the_dot_or_the_case()
    {
        Assert.True(DnsName.Equal("_airclip._tcp.local.", "_AirClip._tcp.LOCAL"));
        Assert.False(DnsName.Equal("_airclip._tcp.local", "_airprint._tcp.local"));
    }

    [Theory]
    [InlineData("桌面-1a2b._airclip._tcp.local.", true)]
    [InlineData("_airclip._tcp.local.", false)]
    [InlineData("evil_airclip._tcp.local.", false)]
    [InlineData("桌面._airprint._tcp.local.", false)]
    public void An_instance_belongs_to_a_service_type_only_on_a_label_boundary(string candidate, bool expected)
    {
        // "evil_airclip._tcp.local" ends with the service type as a string but not as a label, and letting
        // it through would mean browsing for AirClip and finding whatever chose a lookalike name.
        Assert.Equal(expected, DnsName.IsInstanceOf(candidate, "_airclip._tcp.local."));
    }

    [Theory]
    [InlineData("Anna's MacBook 2.0", "Anna's MacBook 2-0")]
    [InlineData("  桌面  ", "桌面")]
    [InlineData("", "AirClip")]
    [InlineData("...", "AirClip")]
    public void A_device_name_becomes_exactly_one_label(string input, string expected)
    {
        Assert.Equal(expected, DnsName.SanitiseLabel(input));
    }

    [Fact]
    public void A_long_chinese_name_is_trimmed_by_bytes_and_stays_valid_utf8()
    {
        // Three bytes per character: cutting at 63 characters would produce a label no resolver accepts,
        // and cutting mid-character would produce bytes that are not UTF-8 at all.
        string label = DnsName.SanitiseLabel(new string('测', 40));

        Assert.True(Encoding.UTF8.GetByteCount(label) <= DnsName.MaxLabelBytes);
        Assert.Equal(21, label.Length);
        Assert.Equal(label, Encoding.UTF8.GetString(Encoding.UTF8.GetBytes(label)));
    }

    [Fact]
    public void A_profile_carries_the_device_id_into_the_instance_name()
    {
        ServiceProfile profile = ServiceProfile.Create(
            "_airclip._tcp.local.", "1a2b3c4d-5e6f", "办公室台式机", 47653, fingerprint: "A1B2C3D4");

        Assert.Equal("_airclip._tcp.local", profile.ServiceType);
        Assert.Equal("办公室台式机-5e6f._airclip._tcp.local", profile.InstanceName);
        Assert.True(DnsName.IsInstanceOf(profile.InstanceName, profile.ServiceType));
        Assert.Equal("airclip-1a2b3c4d.local", profile.HostName);
        Assert.Equal(ServiceProfile.DefaultTtl, profile.Ttl);
        Assert.Contains("id=1a2b3c4d-5e6f", profile.TxtEntries);
        Assert.Contains("name=办公室台式机", profile.TxtEntries);
        Assert.Contains("plat=windows", profile.TxtEntries);
        Assert.Contains("fp=A1B2C3D4", profile.TxtEntries);
    }

    [Fact]
    public void A_profile_without_a_pairing_code_publishes_no_fingerprint()
    {
        ServiceProfile profile = ServiceProfile.Create("_airclip._tcp.local", "abcdefgh", "PC", 47653);

        Assert.DoesNotContain(profile.TxtEntries, entry => entry.StartsWith("fp=", StringComparison.Ordinal));
    }

    [Fact]
    public void Two_devices_with_the_same_name_still_announce_different_instances()
    {
        ServiceProfile first = ServiceProfile.Create("_airclip._tcp.local", "aaaa1111", "办公室", 47653);
        ServiceProfile second = ServiceProfile.Create("_airclip._tcp.local", "bbbb2222", "办公室", 47653);

        Assert.NotEqual(first.InstanceName, second.InstanceName);
        Assert.NotEqual(first.HostName, second.HostName);
    }

    [Fact]
    public void A_discovered_service_reads_itself_out_of_its_txt_record()
    {
        var service = new DiscoveredService
        {
            InstanceName = "手机-9f8e._airclip._tcp.local",
            ServiceType = "_airclip._tcp.local",
            Port = 47653,
            Address = IPAddress.Parse("192.168.1.31"),
            TxtEntries = ["ID=9f8e7d6c", "name=小米手机", "plat=android", "fp=A1B2C3D4"],
        };

        Assert.Equal("9f8e7d6c", service.DeviceId);
        Assert.Equal("小米手机", service.DeviceName);
        Assert.Equal("android", service.Platform);
        Assert.Equal("A1B2C3D4", service.Fingerprint);
        Assert.Equal("手机-9f8e", service.InstanceLabel);
        Assert.True(service.IsDialable);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("192.168.1.31"), 47653), service.EndPoint);
    }

    [Fact]
    public void A_service_with_no_address_or_no_port_is_not_dialable()
    {
        var partial = new DiscoveredService
        {
            InstanceName = "手机._airclip._tcp.local",
            ServiceType = "_airclip._tcp.local",
            Port = 47653,
        };

        Assert.False(partial.IsDialable);
        Assert.Null(partial.EndPoint);
        Assert.False((partial with { Address = IPAddress.Loopback, Port = 0 }).IsDialable);

        // Falls back to the instance label when the responder published no name, and never shows "unknown"
        // where a user expects a device.
        Assert.Equal("手机", partial.DeviceName);
        Assert.Equal("unknown", partial.Platform);
        Assert.Null(partial.Fingerprint);
    }
}
