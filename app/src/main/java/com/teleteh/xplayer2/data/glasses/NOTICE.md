# Third-party knowledge in `data/glasses`

Nothing in this directory is copied source. What *is* borrowed is **wire-format knowledge** —
byte offsets, scale factors, command IDs, axis conventions and calibration-blob layout for XREAL
glasses — reverse-engineered by other people and published under permissive licences. The Kotlin
here is written from scratch against that documentation; this file records where the facts came
from so the debt is visible and so a future reader can re-verify them upstream.

## badicsalex/ar-drivers-rs — MIT

<https://github.com/badicsalex/ar-drivers-rs>, commit `56bfa48` (2026-05-14).
Copyright (c) 2023 Alex Badics. Licensed under the MIT licence; full text at
<https://github.com/badicsalex/ar-drivers-rs/blob/master/LICENSE>.

Used for:

| Fact | Upstream location |
|---|---|
| XREAL Air IMU report layout (signature, timestamp, gyro/accel multiplier-divisor + `i24` triples) | `src/nreal_air.rs` → `ImuDevice::parse_report` |
| Sensor→world axis permutation and signs (`RUB`: `(-x, +z, +y)` for both gyro and accel) | `src/nreal_air.rs` → `ImuDevice::parse_report` |
| Accelerometer readings are in **g** (upstream multiplies by 9.81 to get m/s²) | same |
| `0xAA` IMU command framing: `head, crc32 LE, length = 3 + dataLen, msgId, data` | `src/nreal_air.rs` → `ImuPacket::{serialize, deserialize}` |
| Calibration blob protocol: `0x19`=stream on/off, `0x14`=blob length (`u32` LE), `0x15`=next segment, blob is UTF-8 JSON | `src/nreal_air.rs` → `ImuDevice::{new_device, read_config}` |
| Calibration JSON path `IMU.device_1.{gyro_bias, accel_bias}` and that the bias is **added**, not subtracted | `src/nreal_air.rs` → `ImuDevice::parse_config` |
| Per-model MCU / IMU HID interface numbers and max payload size (Air, Air 2, Air 2 Pro = 3/4/64 B; Air 2 Ultra = 2/0/512 B) | `src/nreal_air.rs` → `AirModel::{mcu_interface, imu_interface, imu_packet_size}` |
| CRC-32 variant used by the framing (standard zlib CRC-32, reflected, `0xEDB88320`) | `src/util.rs` → `crc32_adler` |
| Nreal Light / OV580 report layout (documented for completeness; XPlayer2 does not support it) | `src/nreal_light.rs` → `Ov580::parse_report` |

## TheJackiMonster/nrealAirLinuxDriver — MIT

<https://gitlab.com/TheJackiMonster/nrealAirLinuxDriver>.
Copyright (c) 2023 thejackimonster. This is the project `ar-drivers-rs` itself credits for the
XREAL Air format; consulted directly for the three things `ar-drivers-rs` leaves as a `TODO`.

Used for:

| Fact | Upstream location |
|---|---|
| The **complete** 64-byte report struct, including the magnetometer block (`u16` multiplier, `u32` divisor, three `i16` values) and the trailing CRC + padding | `interface_lib/include/device_imu.h` → `device_imu_packet_t` |
| Magnetometer endianness quirks: multiplier/divisor are **big**-endian, and each `i16` axis has its high byte XOR-ed with `0x80` (`pack16bit_signed_bizarre`) | `interface_lib/src/device_imu.c` → `readIMU_from_packet` |
| Temperature scale — ICM-42688-P, `°C = raw / 132.48 + 25.0` | `interface_lib/src/device_imu.c` → `device_imu_read` |
| Calibration JSON also carries `mag_bias`, `scale_gyro`, `scale_accel`, `scale_mag`, `imu_noises`; gyro bias is in **rad/s** and accel bias in **m/s²**, so both need converting to our units | `interface_lib/src/device_imu.c` → calibration parse + `apply_calibration` |
| IMU message IDs `0x14`…`0x1A` | `interface_lib/include/device_imu.h` |
| Per-product IMU interface / payload-size table, VID `0x3318` | `interface_lib/src/hid_ids.c` |
| That the magnetometer makes the fusion *worse* on these glasses (upstream parses it but feeds `FusionAhrsUpdateNoMagnetometer`) | `interface_lib/src/device_imu.c` → `device_imu_read` |
| Reference fusion tuning: gain 0.5, acceleration rejection 10°, magnetic rejection 20°, 5 s recovery, 1000 Hz assumed sample rate | `interface_lib/src/device_imu.c` → `FusionAhrsSettings` |

## What we did *not* take

The fusion itself is ours: `HeadOrientationTracker` implements a Mahony complementary filter with
explicit gains, not a port of x-io's `Fusion` library (which `nrealAirLinuxDriver` vendors under
its own licence) and not a port of `ar-drivers-rs`, which deliberately ships no fusion at all.
Display/camera calibration matrices, SLAM camera descriptors, firmware update and the MCU packet
protocol are all out of scope here — the MCU side already has its own attribution in
`GlassesProtocol.kt`.
