# scq

Barebones Linux control for **Soundcore Liberty 4 NC (A3947)**: battery, noise canceling modes, and a live listening-level (dB) meter.

Needs the earbuds paired and connected, and `python-dbus` plus `python-gobject` (preinstalled on most desktops).

```sh
./scq status                        # battery (left/right/case), firmware, current mode
./scq anc noise-canceling           # or: transparency, normal
./scq nc manual --level 3           # or: adaptive, transportation
./scq transparency vocal            # or: full
./scq db                            # live meter, Ctrl+C to stop
./scq db --bar --interval 2         # one line per reading, for status bars
```

## How it works

The earbuds use Soundcore's packet protocol:

```
host → buds:  08 ee 00 00 00 [cmd 2 bytes] [total length u16 LE] [body] [checksum]
buds → host:  09 ff 00 00 01 [cmd 2 bytes] [total length u16 LE] [body] [checksum]
checksum = sum of all previous bytes, truncated to 8 bits
```

Packets are sent over RFCOMM channel 7. On firmware 08.61, while a BLE link to the earbuds is also open (BlueZ opens one automatically), the earbuds may answer as GATT notifications on service `014ef5da-…`, characteristic `00008888-…`, instead of over RFCOMM, and they switch between the two. `scq` listens on both.

| Command | Body | Meaning |
|---|---|---|
| `01 01` | none | Request full state (159-byte reply) |
| `06 81` | 7 bytes | Set sound modes: ambient, NC strength (manual << 4 \| adaptive), transparency, NC mode, wind noise, environment detection, transport |
| `20 03` | none | Request current listening level; reply is 1 byte in dB (0 when nothing is playing) |

State offsets (reply body of `01 01`): left/right battery at 2/3 (0–5), charging at 4/5, firmware at 6–15, sound modes at 126–132, case battery at 139.

Protocol details for state and sound modes come from [OpenSCQ30](https://github.com/Oppzippy/OpenSCQ30); the `20 03` listening-level request and the BLE reply path were found by capturing traffic on firmware 08.61.
