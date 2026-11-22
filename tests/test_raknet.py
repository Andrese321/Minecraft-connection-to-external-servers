import unittest

from bedrock_lan_proxy import raknet


class PacketTests(unittest.TestCase):
    def test_ping_roundtrip(self):
        pkt = raknet.build_ping(-5, timestamp=123456)
        self.assertEqual(len(pkt), 33)
        self.assertTrue(raknet.is_ping(pkt))
        self.assertEqual(raknet.parse_ping(pkt), (123456, -5))

    def test_ping_open_connections_variant(self):
        pkt = bytearray(raknet.build_ping(1, 99))
        pkt[0] = 0x02
        self.assertEqual(raknet.parse_ping(bytes(pkt)), (99, 1))

    def test_not_a_ping(self):
        self.assertFalse(raknet.is_ping(b"\x05" + b"\x00" * 40))
        self.assertIsNone(raknet.parse_ping(b"\x01\x00"))

    def test_pong_roundtrip_utf8(self):
        motd = "MCPE;Ñandú ✨;712;1.21.40;0;10;1;Mundo;Survival;1;19132;19133;"
        pkt = raknet.build_pong(77, 0x1122334455667788, motd)
        self.assertEqual(raknet.parse_pong(pkt), (77, 0x1122334455667788, motd))

    def test_bad_pong(self):
        self.assertIsNone(raknet.parse_pong(b"\x1c" + b"\x00" * 40))


class MotdTests(unittest.TestCase):
    def test_parse_fields(self):
        info = raknet.ServerInfo.from_motd(
            "MCPE;Mi Server;712;1.21.40;3;50;999;Lobby;Survival;1;25565;25566;")
        self.assertEqual(info.name, "Mi Server")
        self.assertEqual(info.version, "1.21.40")
        self.assertEqual((info.players, info.max_players), ("3", "50"))
        self.assertEqual(info.level_name, "Lobby")

    def test_for_lan_rewrites_ports_guid_and_name(self):
        info = raknet.ServerInfo.from_motd(
            "MCPE;Mi Server;712;1.21.40;3;50;999;Lobby;Survival;1;25565;25566;")
        fields = info.for_lan(19132, 7, "Hola;Mundo").rstrip(";").split(";")
        self.assertEqual(fields[1], "Hola,Mundo")
        self.assertEqual(fields[6], "7")
        self.assertEqual(fields[10:12], ["19132", "19132"])
        self.assertEqual(fields[2:4], ["712", "1.21.40"])

    def test_for_lan_pads_short_motd(self):
        info = raknet.ServerInfo.from_motd("MCPE;Corto;712;1.21.40;0;10")
        fields = info.for_lan(19132, -1).rstrip(";").split(";")
        self.assertEqual(len(fields), 12)
        self.assertEqual(fields[1], "Corto")
        self.assertEqual(fields[6], str(2**64 - 1))


class AddressTests(unittest.TestCase):
    def test_parse_address(self):
        self.assertEqual(raknet.parse_address("play.ejemplo.net"), ("play.ejemplo.net", 19132))
        self.assertEqual(raknet.parse_address(" 1.2.3.4:19150 "), ("1.2.3.4", 19150))
        self.assertEqual(raknet.parse_address("[2001:db8::1]:19133"), ("2001:db8::1", 19133))
        self.assertEqual(raknet.parse_address("2001:db8::1"), ("2001:db8::1", 19132))
        self.assertEqual(raknet.parse_address("host", 1234), ("host", 1234))

    def test_parse_address_errors(self):
        for bad in ("", "host:0", "host:70000", "host:abc"):
            with self.assertRaises(ValueError):
                raknet.parse_address(bad)


if __name__ == "__main__":
    unittest.main()
