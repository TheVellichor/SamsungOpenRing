
**NOTICE: This document is produced strictly for educational purposes and
as a personal hobby project. It is not intended for commercial use,
circumvention of security measures, or any activity that violates
applicable laws or Samsung's terms of service. The authors take no
responsibility for any use or misuse of the information contained herein.
Use at your own risk.**

---


Samsung Galaxy Ring Protocol                                 N. Viventsov
RFC-SGR-001                                          Independent Research
Category: Informational                                       March 2026


         Samsung Galaxy Ring BLE Communication Protocol
                    Specification (Reverse-Engineered)

Abstract

   This document describes the proprietary Bluetooth Low Energy (BLE)
   communication protocol used by the Samsung Galaxy Ring (SM-Q509) to
   exchange data with its companion application on Android.  The protocol
   employs a channel-multiplexed message architecture built on the
   Samsung Accessory Protocol (SAP) framework, transported over a single
   GATT characteristic pair.  The specification covers connection
   establishment, message framing, channel assignment, gesture detection,
   health data synchronization, firmware updates, and diagnostic
   services.

   This specification was produced through reverse engineering of the
   Galaxy Ring Manager application (com.samsung.android.ringplugin) and
   analysis of captured BLE traffic.  It is published for
   interoperability research and security analysis purposes.

Status of This Memo

   This document is not an Internet Standards Track specification.  It
   describes a proprietary protocol reverse-engineered from a commercial
   product and is published for informational purposes only.

Copyright Notice

   Samsung, Galaxy Ring, and related trademarks are property of Samsung
   Electronics Co., Ltd.  This document is an independent work of
   reverse engineering produced under fair use for research purposes.

Table of Contents

   1.  Introduction . . . . . . . . . . . . . . . . . . . . . . . .  2
   2.  Terminology  . . . . . . . . . . . . . . . . . . . . . . . .  3
   3.  Protocol Overview  . . . . . . . . . . . . . . . . . . . . .  3
   4.  BLE Transport Layer  . . . . . . . . . . . . . . . . . . . .  5
   5.  SAP Message Framing  . . . . . . . . . . . . . . . . . . . .  7
   6.  Channel Multiplexing . . . . . . . . . . . . . . . . . . . . 10
   7.  Connection Lifecycle . . . . . . . . . . . . . . . . . . . . 12
   8.  Gesture Protocol . . . . . . . . . . . . . . . . . . . . . . 15
   9.  Health Data Protocol . . . . . . . . . . . . . . . . . . . . 18
   10. Settings and Configuration . . . . . . . . . . . . . . . . . 23
   11. Firmware Update Protocol . . . . . . . . . . . . . . . . . . 25
   12. Debug and Diagnostic Services  . . . . . . . . . . . . . . . 27
   13. Large Data Transfer  . . . . . . . . . . . . . . . . . . . . 30
   14. Android Integration Points . . . . . . . . . . . . . . . . . 31
   15. Security Considerations  . . . . . . . . . . . . . . . . . . 33
   16. References . . . . . . . . . . . . . . . . . . . . . . . . . 35
   Appendix A.  Observed Wire Traces  . . . . . . . . . . . . . . . 36
   Appendix B.  Live Validation (Second-Client Probe) . . . . . . . 38


1.  Introduction

1.1.  Background

   The Samsung Galaxy Ring is a wearable smart ring that provides health
   monitoring (heart rate, SpO2, skin temperature, sleep tracking, stress
   measurement) and a double-pinch gesture for device control.  The ring
   communicates exclusively over Bluetooth Low Energy with a companion
   application running on Samsung Galaxy smartphones.

   Samsung does not publish a protocol specification or public SDK for
   the Galaxy Ring's gesture or communication capabilities.  The Samsung
   Health Data SDK provides limited access to health metrics but does
   not expose gesture events, connection management, or the underlying
   transport protocol.

1.2.  Scope

   This document specifies the wire-level protocol between the Galaxy
   Ring and its companion application.  It covers:

      - BLE GATT service and characteristic layout
      - The SAP-based message framing format
      - Channel multiplexing and routing
      - Connection establishment and state management
      - Gesture detection and event delivery
      - Health data synchronization
      - Firmware update procedures
      - Debug and diagnostic commands

   This document does not cover the ring's internal firmware, sensor
   fusion algorithms, or the Samsung Health cloud synchronization
   protocol.

1.3.  Methodology

   The protocol was reverse-engineered through three complementary
   methods.  First, the Galaxy Ring Manager APK
   (com.samsung.android.ringplugin, version 2.2.16) was
   decompiled using JADX and analyzed statically.  Second, BLE traffic
   between a Galaxy Ring (SM-Q509, firmware Q50XWWU2AZA4) and a Galaxy S24 Ultra
   was captured using Android's Bluetooth HCI snoop log facility and
   analyzed with Wireshark.  Third, in June 2026, a live second GATT
   client (connecting alongside the official application) was used to
   actively probe the ring -- enumerating its full GATT database,
   issuing commands, performing characteristic reads, and observing the
   notification stream in real time.  This third method validated and
   corrected several earlier inferences; its results are consolidated in
   Appendix B.

   All byte sequences and protocol constants presented in this document
   were verified against these sources unless otherwise noted.  Where the
   live validation in Appendix B contradicts an earlier inference, the
   text has been corrected in place and the change noted.


2.  Terminology

   The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT",
   "SHOULD", "SHOULD NOT", "RECOMMENDED", "NOT RECOMMENDED", "MAY", and
   "OPTIONAL" in this document are to be interpreted as described in
   BCP 14 [RFC2119] [RFC8174] when, and only when, they appear in
   capitalized form, as shown here.

   The following terms are used throughout this document:

   Host:  The Android smartphone running the companion application.

   Ring:  The Samsung Galaxy Ring device.

   SAP:  Samsung Accessory Protocol, the proprietary message framing
      layer used for all application data.

   Channel:  A logical communication stream multiplexed over the single
      GATT characteristic pair.  Each channel carries a distinct class
      of messages (gestures, health data, settings, etc.).

   CCCD:  Client Characteristic Configuration Descriptor, the standard
      BLE mechanism for enabling notifications on a characteristic.


3.  Protocol Overview

3.1.  Architecture

   The protocol is organized as a layered stack.  At the lowest level,
   a BLE GATT connection provides a single bidirectional byte stream
   through one write characteristic (host-to-ring) and one notify
   characteristic (ring-to-host).  Above this, the SAP message layer
   provides framing, message typing, and parameter encoding.  A channel
   multiplexer routes SAP messages to the appropriate application-layer
   handler based on a numeric channel identifier.

      +----------------------------------------------------+
      |            Application Layer                        |
      |  (Gesture, Health, Settings, FOTA, Debug, Find)    |
      +----------------------------------------------------+
      |            Channel Multiplexer                      |
      |  (Channels 1..33, routed by numeric ID)            |
      +----------------------------------------------------+
      |            SAP Message Layer                        |
      |  (Header byte, parameter TLV encoding)             |
      +----------------------------------------------------+
      |            BLE GATT Transport                       |
      |  (TX characteristic for writes, RX for notifs)     |
      +----------------------------------------------------+
      |            BLE Link Layer                           |
      |  (LE Secure Connections, AES-CCM encryption)       |
      +----------------------------------------------------+

3.2.  Message Flow

   To send a command to the ring, the host constructs a SAP message,
   associates it with a channel identifier, and writes the serialized
   bytes to the TX characteristic.  The ring's Smart Ring SDK receives
   the GATT write, demultiplexes by channel, parses the SAP message,
   and dispatches it to the appropriate handler.

   In the reverse direction, the ring sends a GATT notification on the
   RX characteristic.  The host receives the notification via its GATT
   callback, demultiplexes the channel, and invokes the registered
   application-layer handler.

   All messages follow a request-response or notification pattern.  The
   protocol does not provide a general-purpose acknowledgement mechanism
   at the SAP layer; reliability depends on BLE's link-layer guarantees
   and application-specific ACK messages where needed.


4.  BLE Transport Layer

4.1.  GATT Services

   Live GATT discovery (Appendix B.1) shows the ring exposes ten
   services -- a mix of standard Bluetooth SIG services and four
   proprietary ones:

      00001800  Generic Access          (Device Name, Appearance)
      00001801  Generic Attribute       (Service Changed)
      0000180a  Device Information      (Manufacturer Name only)
      0000180d  Heart Rate              (live HR Measurement, see B.3)
      0000180f  (not present on this unit; battery is proprietary)
      00001b1a  Samsung FOTA            (firmware transfer)
      00001b1b  Samsung DATA            (all protocol traffic)
      79d34772-bc93-4360-8244-42b50a4a2b27  (proprietary)
      eedd5e73-6aa8-4673-8219-398a489da87c  (proprietary)
      0000fff0  (proprietary)
      0000fd69  (proprietary)

   The Data Service (1b1b) carries all channel-multiplexed protocol
   traffic.  The FOTA Service (1b1a) is used exclusively for firmware
   transfers and exposes the same two characteristic UUIDs as the Data
   Service.  Notably, the ring ALSO presents a standard Heart Rate
   Service, allowing any GATT client to read live heart rate without the
   proprietary protocol (Appendix B.3).  The earlier inference of "three
   services" reflected only those referenced by the companion app.

4.2.  Characteristics

   Within the Data Service, two characteristics carry all application
   data:

      Notify (Ring->Host)   797ae4e9-2e58-4fe8-b48d-b5c79599fb9b
      Write  (Host->Ring)   63e30bad-4206-4596-839f-e47cbf7a4b5d

   CORRECTION (validated live, Appendix B.2): earlier revisions of this
   document labelled these the other way around (797ae4e9 as the write
   characteristic).  Live discovery shows 797ae4e9 has properties
   Read + Notify (0x12) and is the characteristic on which ALL ring-to-
   host notifications arrive (gesture events, battery, heartbeat), while
   63e30bad has property Write-Without-Response (0x04) and is the target
   of all host-to-ring writes.

   The Write characteristic (63e30bad) accepts Write Without Response
   (ATT opcode 0x52) operations from the host.  The Notify characteristic
   (797ae4e9) delivers data from the ring via Handle Value Notification
   (ATT opcode 0x1b) and is also readable.  An implementation MUST select
   the write characteristic by its Write-Without-Response property rather
   than by hardcoded UUID role, as the role labels were historically
   reversed.

   A Client Characteristic Configuration Descriptor (UUID 0x2902) is
   associated with the RX characteristic.  The host MUST write the value
   0x0100 to this descriptor to enable notifications before any data
   exchange can occur.

4.3.  Handle Assignments

   The following handle assignments were observed in a live capture.
   Handle values are assigned during GATT service discovery and MAY
   differ across firmware versions or connection sessions.

       Handle   Direction     Role
       ------   ---------     ----
       0x0020   Ring->Host    Battery level (single byte, 0-100)
       0x0023   Ring->Host    Primary data (RX characteristic)
       0x0024   Ring->Host    Secondary notifications / CCCD status
       0x0026   Host->Ring    Primary commands (TX characteristic)
       0x0028   Ring->Host    Connection state (0x00/0x01)

   Handle 0x0023 carries all channel-multiplexed data in both
   captures analyzed.  Handle 0x0026 is the corresponding write
   target.  Handle 0x0024 emits occasional notifications including
   CCCD status changes (0xff0320, 0xff0402, 0xff0400).

   Note: when a second GATT client connects (as in the multi-client
   test described in Section 15), the handle assignments for the
   second client's service discovery MAY differ from the primary
   client's handles.  The second client MUST use UUID-based
   characteristic lookup rather than hardcoded handle values.

4.4.  MTU

   The ring requests an MTU of 498 bytes.  After accounting for the
   3-byte ATT header, each ATT payload carries up to 495 bytes.  For
   large data transfers (Section 13), an additional 8-byte framing
   header reduces the usable payload to 487 bytes per fragment.

   An implementation MUST support an MTU of at least 498 bytes when
   connecting to the ring.  The ring has not been observed to negotiate
   a smaller MTU.


5.  SAP Message Framing

5.1.  Header Byte

   Every SAP message begins with a single header byte.  The header
   encodes three fields:

       0
       0 1 2 3 4 5 6 7
      +-+-+-+-+-+-+-+-+
      |F|T|  MSG_ID   |
      +-+-+-+-+-+-+-+-+

      F (Format, 1 bit):
         0 = Fixed-length message.  The message carries a single
             parameter as a fixed-size value immediately following the
             header.
         1 = Variable-length message.  The message carries a counted
             sequence of typed parameters.

      T (Type, 1 bit):
         0 = Request.  Sent by the initiating party.
         1 = Response.  Sent in reply to a request.

      MSG_ID (Message Identifier, 6 bits):
         Identifies the message within the scope of the channel that
         carries it.  Values 0 through 63 are representable.  The
         meaning of each MSG_ID is channel-specific and defined in the
         relevant section of this document.

5.2.  Fixed-Length Messages

   When the Format bit is 0, the message body consists of exactly two
   bytes following the header:

       0                   1                   2
       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
      |    Header     |   Param ID    |  Param Value  |
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

   The Param ID identifies which parameter is being set or reported.
   The Param Value is a single unsigned byte.

   Note that SAP messages are always wrapped in a channel envelope
   (Section 6.3) when transmitted on the wire.  For example, a
   fixed-length gesture enable command appears on the wire as:

       0x16 0x16 0x00
       ^^^^ ^^^^       channel envelope (Channel 22)
                 ^^^^   SAP header (MSG_ID=0, enable)

   Some fixed-length messages omit the Param ID and Value fields
   entirely, consisting of only the channel envelope plus the SAP
   header byte.

5.3.  Variable-Length Messages

   When the Format bit is 1, the second byte indicates the number of
   parameters, followed by a sequence of TLV-encoded parameter entries:

       0                   1                   2
       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
      |    Header     | Param Count   | Params ...
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

   Each parameter entry is encoded as:

      [Param ID (1 byte)] [Type-dependent length] [Value]

   The encoding of the length and value depends on the parameter's
   data type.

5.4.  Parameter Data Types

   The following data types are defined for parameter values.  All
   multi-byte integers are encoded in little-endian byte order.

      Type   Name       Size       Encoding
      ----   ----       ----       --------
      0      BOOLEAN    1 byte     0x00 = false, 0x01 = true
      1      BYTE       1 byte     Unsigned integer
      2      SHORT      2 bytes    Unsigned, little-endian
      3      INT        4 bytes    Unsigned, little-endian
      4      LONG       8 bytes    Unsigned, little-endian
      5      STRING     Variable   Length-prefixed UTF-8
      6      CUSTOM     Variable   Length-prefixed raw octets

   For types 5 (STRING) and 6 (CUSTOM), the value is preceded by a
   length field.  The size of the length field itself varies:

      Length Tag    Length Field Size
      ----------   ----------------
      10           1 byte  (values 0..255)
      11           2 bytes (values 0..65535, little-endian)
      12           4 bytes (values 0..2^32-1, little-endian)

5.5.  Byte Order

   All multi-byte integer fields in the protocol, including parameter
   values, length prefixes, and chunk indices in the large data transfer
   protocol, MUST be encoded in little-endian byte order.  String values
   MUST be encoded as UTF-8.


6.  Channel Multiplexing

6.1.  Channel Identifiers

   The SAP layer multiplexes up to 33 logical channels over the single
   GATT characteristic pair.  Each channel serves a distinct
   application-layer function.  The following channels have been
   identified:

      Channel   Name         Function
      -------   ----         --------
      1         Control      Connection control, always permitted
      2         Find-Ring    "Find My Ring" (host locates ring)
      10        Health       Health data synchronization
      11        Settings     Device configuration and status
      12        Debug        Diagnostics, logging, shell access
      20        Logging      Samsung Analytics telemetry
      21        FOTA         Firmware Over The Air updates
      22        Gesture      Gesture detection and control
      23        (unknown)    Periodic status (~10 min interval) [1]
      31        Find-Device  "Find My Device" (ring locates host)
      32        (unknown)    Account binding, text fields [1]
      33        Sensor-Raw   Raw PPG/HRM sensor control

   [1] Channels 23 and 32 were observed on the wire but are not
   referenced in the decompiled companion application source.  They
   may be handled at a lower SDK layer or by the ring's firmware
   directly.

6.2.  Channel Routing

   When the host transmits a message, it associates the serialized SAP
   bytes with a channel identifier.  The Smart Ring SDK wraps this into
   an internal SRMessageData structure containing the device identifier,
   source channel, destination channel, and payload, then enqueues it
   for GATT transmission.

   The host MUST NOT transmit on any channel other than Channel 1
   unless the connection is in the CONNECTED state (see Section 7.2).
   Two exceptions exist: Channel 1 messages are always permitted
   regardless of connection state, and FOTA data (Channel 21) bypasses
   the connection state check during firmware transfers.

6.3.  Channel Envelope

   All messages on handle 0x0023 (ring-to-host) and handle 0x0026
   (host-to-ring) are prefixed with a two-byte channel envelope:

       0                   1
       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
      |  Channel ID   |  Channel ID   |
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

   The channel identifier is repeated twice.  The remainder of the
   payload is a SAP message as defined in Section 5, with the MSG_ID
   interpreted in the context of the identified channel.

   This envelope applies to BOTH directions.  Host-to-ring commands
   MUST include the channel prefix; the ring ignores writes that omit
   it.

   Observed channel prefixes on the wire:

      Prefix     Channel   Meaning
      ------     -------   -------
      0x0a 0x0a  10        Health data
      0x0b 0x0b  11        Settings
      0x14 0x14  20        SA logging
      0x16 0x16  22        Gestures
      0x17 0x17  23        Periodic status (10-minute heartbeat)
      0x1f 0x1f  31        Device info / Find
      0x20 0x20  32        Text fields (account binding)

   Channels 23 and 32 were observed only in wire captures; no
   corresponding handler was found in the decompiled companion
   application.  Channel 23 emits a periodic `1717010303` message
   approximately every 10 minutes.  Channel 32 carries UTF-8 text
   including the user's email address during initial handshake.
   Both may be processed at a firmware or SDK layer below the
   application code.


7.  Connection Lifecycle

7.1.  Discovery

   The ring advertises over BLE using a device name containing the
   substring "Ring".  The host identifies the ring during BLE scanning
   by matching this name pattern against the constant MODEL_NAME_RING.
   Once discovered, the host initiates a BLE connection.

7.2.  Connection States

   The connection passes through three primary states:

      State          Value   Description
      -----          -----   -----------
      DISCONNECTED   0       No BLE connection exists.
      CONNECTING     1       BLE connection initiated, not yet ready.
      CONNECTED      2       GATT discovery complete, data exchange
                             permitted.

   The transition from CONNECTING to CONNECTED occurs after GATT
   service discovery completes and all required CCCD descriptors have
   been written successfully.

7.3.  CCCD Subscription

   Upon successful GATT discovery, the host MUST enable notifications
   by writing 0x0100 to the CCCD associated with the RX characteristic.
   In the observed session, CCCD writes were issued to handles 0x0004,
   0x0021, 0x0024, 0x0025, and 0x0029.  Failure to subscribe results
   in connection substate DESCRIPTOR_SUBSCRIPTION_FAILED (250).

7.4.  Initial Handshake

   After CCCD subscription, the ring and host exchange a burst of
   initialization messages.  The observed sequence proceeds as follows:

      Ring  ->  Host:  Device configuration (0xa7 prefix)
                       Contains manufacturer name, device name,
                       model number, serial number, product
                       identifiers.

      Ring  ->  Host:  Property records (0x01 prefix)
                       Firmware version, hardware version, ring
                       size, charging specifications, capacity.

      Host  ->  Ring:  Account binding (0x20 prefix)
                       User email address for device association.

      Ring  ->  Host:  Configuration store (0x14 prefix)
                       Key-value pairs (RING0001..RING0014) with
                       device-specific settings.

      Ring  ->  Host:  Battery level on handle 0x0020.

   The host then sends time synchronization (RTC), health measurement
   preferences, and gesture enable/disable commands.

7.5.  Wearing Detection

   The ring continuously monitors whether it is on the wearer's finger.
   Changes in wearing state are reported via Channel 11, MSG_ID 52
   (MSG_WEARING_STATE_INFO), with Param ID 55 carrying a single byte:
   0x00 for removed, 0x01 for on-finger.

   The host broadcasts wearing state changes to the Android system via
   the intent action:

      com.samsung.android.ringPlugin.intent.action.RING_WEAR_STATUS

   with a boolean extra "ringWearState".

7.6.  Disconnection

   Disconnection may occur due to BLE signal loss, user action, or
   Bluetooth being disabled.  The host classifies the cause using
   substates (Section 7.2) and broadcasts:

      com.samsung.android.wearable.action.ACTION_WEARABLE_RING_DISCONNECTED

   Disconnection notifications are delayed by 500 milliseconds to
   avoid spurious alerts from transient signal fluctuations.  When the
   ring's battery level is below 15%, disconnect notifications are
   suppressed entirely.  Notifications are also suppressed while a
   firmware update is in progress.


8.  Gesture Protocol

8.1.  Overview

   The Galaxy Ring supports a single gesture: a double-pinch performed
   by tapping the thumb and index finger together twice in quick
   succession.  The gesture is detected by the ring's onboard
   accelerometer and transmitted to the host over Channel 22.

   The host dispatches the gesture to one of several receivers
   depending on application state: the camera app (to trigger the
   shutter), the alarm app (to dismiss an active alarm), or AR glasses
   (for extended reality control).

8.2.  Message Identifiers

   Channel 22 defines three message identifiers:

      MSG_ID   Name                           Direction
      ------   ----                           ---------
      0        ENABLE_GESTURE_MSG             Host -> Ring
      1        DISABLE_GESTURE_MSG            Host -> Ring
      2        PINTCH2_GESTURE_DETECTION_MSG  Ring -> Host

8.3.  Enabling Gesture Detection

   To enable gesture detection, the host writes three bytes to the TX
   characteristic.  The format matches the channel-prefixed envelope
   used for all Channel 22 messages:

       0                   1                   2
       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
      |  Chan (0x16)  |  Chan (0x16)  |  MSG_ID=0x00  |
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

   Wire bytes: 0x16 0x16 0x00

   The ring acknowledges with a notification containing bytes
   0x16 0x16 0x40 0x00 0x01, where byte 2 (0x40) indicates a response
   (Type bit set) to MSG_ID 0, and byte 4 (0x01) indicates success.

   The host SHOULD send this command after each connection
   establishment to ensure gesture detection is active.

   The channel envelope (bytes 0-1) is REQUIRED on all writes.  The
   ring silently ignores commands that omit the channel prefix.

8.4.  Disabling Gesture Detection

   Disabling follows the same three-byte format with MSG_ID 1:

       0                   1                   2
       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
      |  Chan (0x16)  |  Chan (0x16)  |  MSG_ID=0x01  |
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

   Wire bytes: 0x16 0x16 0x01

8.5.  Gesture Detection Event

   When the ring detects a double-pinch, it sends a notification on
   handle 0x0023 with the following format:

       0                   1                   2
       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
      |  Chan (0x16)  |  Chan (0x16)  |  MSG_ID=0x02  |
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
       3                   4                   5
       4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
      |   Seq Counter |   Reserved=0  |  Reserved=0   |
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
       6
       8 9 0 1 2 3 4 5
      +-+-+-+-+-+-+-+-+
      |  Reserved=0   |
      +-+-+-+-+-+-+-+-+

   Total length: 7 bytes.

   Bytes 0-1:  Channel identifier (0x16 = 22 decimal), repeated.

   Byte 2:     MSG_ID 0x02 (PINTCH2_GESTURE_DETECTION_MSG).

   Byte 3:     Sequence counter.  An unsigned byte that increments
               monotonically with each gesture event.  The counter
               persists across the connection session.  When the
               counter reaches 0xFF, the wrapping behavior has not
               been observed.

   Bytes 4-6:  Reserved, observed as 0x00 in all captures.

   An implementation detecting gestures SHOULD monitor handle 0x0023
   for notifications matching the byte pattern:

      0x16 0x16 0x02

   The third byte (0x02) distinguishes gesture events from other
   Channel 22 messages.

8.6.  Gesture Counter

   The gesture counter at wire byte 3 is an unsigned integer that
   increments with each gesture event across the lifetime of the BLE
   connection.  Values observed range from 0x08 to 0x1c in a single
   session.

   Samsung's companion application extracts this counter as a 4-byte
   big-endian integer from bytes 3-6 of the wire payload (i.e., SAP
   payload bytes 1-4 after stripping the channel envelope).  For
   practical purposes, byte 3 alone is sufficient for deduplication
   since the upper bytes have only been observed as zero.

8.7.  Gesture Receivers

   The host maintains a set of named receivers that compete for gesture
   delivery.  Priority is as follows (highest first):

      1. Alarm     - If an alarm or timer is actively firing, the
                     gesture dismisses it.  All other receivers are
                     suppressed.
      2. Camera    - If the camera application is in the foreground,
                     the gesture triggers the shutter.
      3. Glasses   - If AR glasses are connected and using gestures,
                     the gesture is forwarded to the glasses.
      4. Tutorial  - If the gesture tutorial screen is open, the
                     gesture is consumed for demonstration purposes.

   Only one receiver is active at a time.  The alarm receiver
   unconditionally suppresses all others when active.


9.  Health Data Protocol

9.1.  Overview

   Channel 10 carries all health-related data between the ring and
   host.  The ring performs continuous or periodic measurements of
   various biometric signals and transmits the results to the host for
   processing, storage, and display in the Samsung Health application.

9.2.  Request Headers

   Health data messages use the following request-response header
   pairs:

      Request Header   Response Header   Function
      --------------   ---------------   --------
      1                65 (0x41)         Check measurement status
      3                67 (0x43)         Request health data
      0x82 (-126)      66 (0x42)         Synchronize data records
      4                68 (0x44)         Wearable message exchange
      5                69 (0x45)         Workout priority control

9.3.  Health Feature Types

   Each health measurement is identified by a numeric feature type.
   The following types have been identified:

      Type   Feature                 Description
      ----   -------                 -----------
      5      DEVICE_INFO             Device information record
      7      PEDO_STEP_COUNT         Step count
      8      PEDO_EVENT              Pedometer event trigger
      9      PEDO_RECOMMENDATION     Activity recommendation
      10     HEART_RATE              Heart rate (BPM)
      11     USER_PROFILE            User profile data
      12     EXERCISE_AUTO           Auto-detected exercise session
      13     EXERCISE_PACESETTER     Pace tracking data
      14     WATER_INTAKE            Water intake log
      15     CAFFEINE_INTAKE         Caffeine intake log
      16     SLEEP                   Sleep session summary
      17     SLEEP_STAGE             Sleep stage breakdown
      18     SLEEP_DATA              Detailed sleep metrics
      19     STRESS                  Stress level score
      20     STRESS_HISTOGRAM        Stress score distribution
      21     STRESS_BREATHING        Guided breathing session
      136    ALERTED_HEART_RATE      Abnormal heart rate alert
      148    MOVEMENT                Movement/activity classification
      149    RESPIRATION             Respiration rate
      150    HRV                     Heart rate variability
      225    SKIN_TEMPERATURE        Skin temperature reading
      226    OXYGEN_SATURATION       Blood oxygen (SpO2)
      227    SLEEP_RAW_DATA          Raw sleep sensor stream

9.4.  Wearable Message Routing

   Within Channel 10, messages are further routed to subsystem handlers
   identified by a receiver ID:

      ID   Receiver                          Direction
      --   --------                          ---------
      1    Sleep (host to ring)              Host -> Ring
      2    Sleep (ring to host)              Ring -> Host
      3    Sport/exercise                    Ring -> Host
      4    Inactivity alert                  Ring -> Host
      5    Power saving mode                 Ring -> Host
      6    On-demand heart rate              Bidirectional
      7    On-demand stress                  Bidirectional
      8    Workout priority                  Bidirectional
      9    Wearable framework                Bidirectional
      10   On-demand SpO2                    Bidirectional

9.5.  Heart Rate Parameters

      Param ID   Name                    Type
      --------   ----                    ----
      32         ACTUAL_VALUE            INT (BPM)
      33         MIN_VALUE               INT (BPM)
      34         MAX_VALUE               INT (BPM)
      35         HEART_BEAT_COUNT        INT
      36         BINNING_DATA            CUSTOM (histogram)
      37         TAG_ID                  INT
      21316      TAG_ID_RESTING_HR       INT

9.6.  Blood Oxygen (SpO2) Parameters

      Param ID   Name                    Type
      --------   ----                    ----
      169        SPO2                    INT (percentage)
      170        LOW_DURATION            INT (seconds)
      171        MIN                     INT (percentage)
      172        MAX                     INT (percentage)
      173        BINNING                 CUSTOM (histogram)
      174        HEART_RATE              INT (concurrent BPM)

9.7.  Skin Temperature Parameters

      Param ID   Name                    Type
      --------   ----                    ----
      165        TEMPERATURE             INT (scaled value)
      166        HR_RRI                  INT (R-R interval)
      1          START_TIME              LONG (epoch ms)
      2          STOP_TIME               LONG (epoch ms)
      3          TIME_OFFSET             INT (timezone offset)

9.8.  Sleep Parameters

      Param ID   Name                        Type
      --------   ----                        ----
      1          SLEEP_START_TIME            LONG (epoch ms)
      2          SLEEP_STOP_TIME             LONG (epoch ms)
      80         EFFICIENCY                  INT (percentage)
      81         HAS_SLEEP_DATA              BOOLEAN
      87         SLEEP_LATENCY               INT (minutes)
      88         BEDTIME_DETECTION_DELAY     INT
      89         WAKEUPTIME_DETECTION_DELAY  INT

9.9.  Stress Parameters

      Param ID   Name                    Type
      --------   ----                    ----
      96         SCORE                   INT (0-100)
      97         TAG_ID                  INT
      98         BINNING_DATA            CUSTOM
      102        SCORE_MIN               INT
      103        SCORE_MAX               INT
      104        BASE_HR                 INT (BPM)

9.10.  Pedometer Parameters

      Param ID   Name                    Type
      --------   ----                    ----
      16         STEP_COUNT              INT
      17         DISTANCE                INT (meters)
      18         SPEED                   INT
      19         CALORIES                INT
      20         RUN_STEPS               INT
      21         WALK_STEPS              INT
      22         DURATION                INT (seconds)

9.11.  Health Measurement Configuration

   The host configures which health features the ring actively monitors
   by sending settings on Channel 11.  The following toggle keys control
   measurement behavior:

      Key   Feature                       Default
      ---   -------                       -------
      1     Heart rate measurement period  (varies)
      6     Body detection during sleep    Enabled
      7     Skin temperature monitoring    Enabled
      8     Stress monitoring              Enabled
      9     Dynamic workout detection      Enabled
      10    Walking detection              Enabled
      11    Running detection              Enabled
      12    Dynamic activity detection     Enabled
      13    Sleep skin temperature         Enabled


10.  Settings and Configuration

10.1.  Overview

   Channel 11 carries device settings, battery information, and
   status reports.  Unlike the health channel, settings messages use
   simpler encodings and shorter payloads.

10.2.  Message Identifiers

      MSG_ID   Name                       Direction
      ------   ----                       ---------
      0        MSG_RTC_INFO_SET           Host -> Ring
      2        MSG_BATTERY_INFO           Ring -> Host
      3        MSG_SEND_BATTERY_SYNC      Host -> Ring
      4        MSG_CRADLE_INFO            Ring -> Host
      5        MSG_RING_TEMPERATURE_INFO  Ring -> Host
      6        MSG_CRADLE_HW_INFO         Ring -> Host
      7        MSG_SEM_STATE_INFO         Bidirectional
      32       MSG_FULL_SETTING_INFO      Host -> Ring
      33       MSG_RESET_RING             Host -> Ring
      52       MSG_WEARING_STATE_INFO     Ring -> Host

10.3.  Battery Reporting

   The ring reports battery status via two mechanisms.  A simple
   battery level notification is sent on GATT handle 0x0020 as a
   single byte (0-100, representing percentage).  Additionally,
   Channel 11 MSG_ID 2 (MSG_BATTERY_INFO) carries structured battery
   data with the following parameters:

      Param ID   Name                          Type
      --------   ----                          ----
      5          BATTERY_LEVEL_INFO            BYTE (0-100)
      6          BATTERY_CHARGING_INFO         BOOLEAN
      7          CRADLE_BATTERY_LEVEL_INFO     BYTE (0-100)
      8          CRADLE_BATTERY_CHARGING_INFO  BOOLEAN

   The host requests a battery report by writing the three-byte message
   0x0b 0x0b 0x03 (Channel 11, MSG_SEND_BATTERY_SYNC).  This request is
   serviced even for a second (non-primary) GATT client.  Validated wire
   form of the report (live):

      0b 0b 42 06 00 05 3f 07 ff 08 ff

   Byte 2 is the SAP header.  The ring has been observed using BOTH 0x02
   (request form) and 0x42 (response form, with the Type/response bit
   set) for this message; a parser SHOULD match on the low six bits
   (header & 0x3F == 0x02).  Decoding the remainder as parameter pairs:
   param 6 (charging) = 0x00 (false), param 5 (level) = 0x3f = 63%,
   param 7 = 0xff and param 8 = 0xff, where 0xff indicates the cradle
   field is not present (ring off-cradle).

10.4.  Time Synchronization

   The host SHOULD send an RTC synchronization message (MSG_ID 0) to
   the ring upon each connection establishment.  This ensures the
   ring's internal clock remains accurate for timestamping health
   measurements and sleep tracking.

10.5.  Factory Reset

   MSG_ID 33 (MSG_RESET_RING) commands the ring to perform a factory
   reset, erasing all stored data and returning to an unpaired state.
   This message MUST only be sent in response to an explicit user
   action.


11.  Firmware Update Protocol

11.1.  Overview

   Channel 21 carries firmware update (FOTA) data.  The update process
   is a multi-phase operation involving a host-supplied CRC32 integrity
   check, binary transfer, and installation.

11.2.  Progress States

   The firmware update proceeds through the following states:

      State               Value   Description
      -----               -----   -----------
      IDLE                0       No update in progress.
      CRC_EXCHANGE        1       A whole-image CRC32 supplied by the
                                  host is exchanged as an integrity
                                  check before transfer.  There is no
                                  block-level differencing.
      COPY_IN_PROGRESS    2       Firmware binary is being
                                  transferred using the large data
                                  protocol (Section 13).
      COPY_FINISHED       3       Transfer is complete; ring is
                                  verifying integrity.
      INSTALLING          4       Ring is applying the firmware
                                  update.  The ring MAY reboot
                                  during this phase.

11.3.  Firmware Types

   The ring accepts two categories of firmware:

      Type   Name   Description
      ----   ----   -----------
      0      NET    Network/modem firmware
      1      AP     Application processor firmware

11.4.  Transfer Behavior

   FOTA data is flagged specially in the transport layer, bypassing the
   normal connection state check (Section 6.2) and suppressing
   disconnection notifications (Section 7.6).  Firmware binaries are
   transferred using the large data protocol described in Section 13.

   The ring does not verify a cryptographic signature over the firmware
   image at the transport layer.  Image acceptance is gated only by a
   container magic value, the host-supplied CRC32, and a size check;
   firmware authenticity is enforced separately by the ring's bootloader
   at install time.


12.  Debug and Diagnostic Services

12.1.  Overview

   Channel 12 provides a comprehensive diagnostic interface to the
   ring's internal state.  It supports log retrieval, sensor data
   dumps, device control commands, and a shell command interface.

   These commands are used by Samsung's hidden diagnostic menu within
   the Ring Manager application and are not exposed through any public
   interface.

12.2.  Message Identifiers

      MSG_ID   Name                          Description
      ------   ----                          -----------
      1        LOGDUMP_INIT                  Begin log dump
      2        LOGDUMP_TRANSFER              Transfer log data
      5        SENSORDUMP_INIT               Begin sensor dump
      6        SENSORDUMP_TRANSFER           Transfer sensor data
      7        SENSORDUMP_STOP               Halt sensor dump
      9        GET_HW_VERSION_INFO           Query hardware version
      18       GET_TSP_VERSION_INFO           Query TSP version
      30       MSG_READ_CSV_FILE             Read CSV from ring storage
      32       MSG_ACT_CMD_DATA              Action command
      33       MSG_ACT_RING_BAT_STATUS       Battery diagnostics
      34       MSG_HRM_RAW_TRANSFER          Raw heart rate data stream
      36       FACTORY_RESET                 Factory reset (debug path)
      37       CORE_LOGDUMP_INIT             Core dump initialization
      38       CORE_LOGDUMP_TRANSFER         Core dump transfer
      39       REBOOT_RING                   Reboot the ring
      43       CORE_LOGDUMP_INIT_V2          Core dump init (v2)
      45       CORE_LOGDUMP_TRANSFER_V2      Core dump transfer (v2)
      46       RING_MEMORY_INFO_REQ          Query ring memory information
      49       ECHO_TEST_MSG                 Echo test (loopback)
      51       SHELL_COMMAND_MSG             Execute shell command
      54       ON_SENSOR_STATUS_INFO         Query sensor status
      55       DEBUG_BATTERY_STATISTICS_SYNC Battery statistics
      56       ON_DEMAND_HEALTH_INFO         Trigger health measurement

12.3.  Response Codes

   Debug commands use a simple acknowledgement scheme:

      Code   Name              Meaning
      ----   ----              -------
      0      ACK               Command accepted and executed.
      1      NACK              Command rejected.
      2      ACKNOWLEDGEMENT   Data received, transfer may continue.

12.4.  Shell Command Interface

   MSG_ID 51 (SHELL_COMMAND_MSG) accepts a shell command string and
   executes it on the ring's embedded processor.  The command string
   is sent as a variable-length STRING parameter.  The ring executes
   the command but does NOT return its output over this channel: the
   response carries only a status byte, and command stdout is routed to
   a separate console backend (observed empty over BLE).

   This interface is gated behind the hidden diagnostic menu and
   is not accessible through normal application flow.  No
   authentication beyond the BLE bond is required to execute shell
   commands.

12.5.  Ring Reboot

   MSG_ID 39 (REBOOT_RING) commands the ring to perform an immediate
   reboot.  The ring disconnects from BLE before rebooting.  No
   confirmation prompt is implemented at the protocol level.


13.  Large Data Transfer

13.1.  Overview

   Payloads exceeding the MTU are fragmented using a chunked transfer
   protocol.  This mechanism is used primarily for firmware updates
   (Section 11), log dumps (Section 12), and bulk health data
   synchronization.

13.2.  Fragment Header

   Each fragment carries an 8-byte header preceding the payload:

       0                   1                   2                   3
       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
      |        Fragment Index          |         Total Fragments       |
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
      |                      Total Data Length                        |
      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

   Fragment Index (16 bits):
      Zero-based index of the current fragment.

   Total Fragments (16 bits):
      Total number of fragments in this transfer.

   Total Data Length (32 bits):
      Total length in bytes of the complete, unfragmented payload.

   All fields are little-endian.

13.3.  Fragment Payload

   The maximum payload per fragment is:

      MTU - ATT_HEADER - FRAGMENT_HEADER = 498 - 3 - 8 = 487 bytes

   The final fragment MAY carry fewer than 487 bytes.

13.4.  Reliability

   The receiver sends an ACK after each fragment.  If no ACK is
   received within a timeout period, the sender retransmits.  A NACK
   causes the sender to abort the transfer.  Progress is reported
   through a callback interface providing (current_fragment,
   total_fragments) to the application layer.


14.  Android Integration Points

14.1.  Gesture Broadcast

   Upon receiving a gesture event, the host broadcasts an Android
   intent to the system:

      Action:      com.samsung.android.ring.GESTURE_ACTION
      Permission:  com.samsung.android.ring.permission
                      .INTERCEPT_GESTURE_ACTION

   The intent carries boolean extras indicating which receiver should
   handle the gesture ("camera", "alarm", "glasses", "gesture_tutorial",
   "gesture_test") and an integer extra "gesture_count" containing the
   sequence counter.

   The permission is believed to be signature-level, restricting
   reception to applications signed with Samsung's platform certificate.

14.2.  External Gesture Service

   An AIDL-based service allows external applications to register for
   gesture callbacks.  The service interface provides methods to
   activate and deactivate gesture detection and to register a listener
   that receives onRingGesture(pkgName, gestureId) callbacks.

   Access to this service is restricted by a hardcoded package
   allowlist.  As of the analyzed firmware, only the package
   "com.samsung.android.gesturetest" is permitted.  All other callers
   are rejected.

14.3.  Connection State Broadcasts

      Connected:     com.samsung.android.wearable.action
                        .ACTION_WEARABLE_RING_CONNECTED
      Disconnected:  com.samsung.android.wearable.action
                        .ACTION_WEARABLE_RING_DISCONNECTED


15.  Security Considerations

   The Samsung Galaxy Ring protocol relies on BLE link-layer encryption
   for confidentiality and integrity of all data in transit.  When two
   devices bond using LE Secure Connections, the BLE stack encrypts all
   traffic with AES-CCM.  No additional application-layer encryption,
   authentication, or integrity protection is applied to SAP messages.

   This architectural choice means that the security of all ring
   communications -- including health data, user email addresses, device
   serial numbers, and shell command execution -- depends entirely on
   the strength of the BLE bond.  An attacker who compromises the BLE
   pairing (through an active man-in-the-middle of the Just-Works
   pairing exchange, exploitation of BLE implementation vulnerabilities,
   or physical
   access to one of the bonded devices) gains full access to all
   protocol capabilities.

   Several specific concerns merit attention:

   Plaintext Personal Data.  The initial handshake (Section 7.4)
   transmits the user's email address in cleartext within the SAP
   message payload.  While this is encrypted at the BLE layer, it is
   visible to any process with access to the Bluetooth HCI log, which
   can be enabled without root access via Android Developer Options.

   Unauthenticated Debug Commands.  The debug channel (Section 12)
   provides powerful capabilities including shell command execution
   (MSG_ID 51), device reboot (MSG_ID 39), and factory reset (MSG_ID
   36).  No authentication mechanism beyond the BLE bond is required
   to invoke these commands.  Any application with access to the GATT
   characteristics can issue debug commands.

   Open Characteristic Permissions.  The proprietary write and notify
   characteristics carry no ATT-layer access permissions (no encryption
   or authentication bit).  The ring dispatches an incoming channel
   write to its handler unconditionally; only the outbound notification
   path is gated on an established bond.  Confidentiality and command
   access therefore rest on the link-layer bond alone, with no defense
   in depth at the attribute layer.

   Unauthenticated Firmware Transport.  The firmware update channel
   (Section 11) performs no on-device signature verification of the
   image; it checks only a container magic value, a host-supplied
   CRC32, and a size bound.  Authenticity is enforced solely by the
   ring's bootloader at install time.  A peer able to open a FOTA
   session can therefore stage arbitrary image bytes; whether those
   bytes can be made to boot depends on the separate bootloader
   signature check.

   Static Package Allowlist.  The external gesture service (Section
   14.2) restricts access using a hardcoded package name.  Package
   names are not a reliable authentication mechanism on Android, as
   any application can declare an arbitrary package name.  An
   application masquerading as "com.samsung.android.gesturetest" could
   potentially bind to the gesture service without Samsung's platform
   signature.

   Health Data Sensitivity.  Heart rate, blood oxygen, sleep patterns,
   stress levels, and skin temperature constitute sensitive health
   information.  This data traverses the BLE link protected only by
   the link-layer encryption.  Implementations processing this data
   SHOULD apply appropriate access controls and storage encryption on
   the host device.

   Gesture Broadcast Permission.  The gesture broadcast (Section 14.1)
   is protected by a custom permission.  If this permission is
   declared at the signature protection level, only Samsung-signed
   applications can receive it.  If it is declared at a lower
   protection level, any installed application could monitor the
   user's gesture activity.


16.  References

16.1.  Normative References

   [RFC2119]  Bradner, S., "Key words for use in RFCs to Indicate
              Requirement Levels", BCP 14, RFC 2119,
              DOI 10.17487/RFC2119, March 1997.

   [RFC8174]  Leiba, B., "Ambiguity of Uppercase vs Lowercase in
              RFC 2119 Key Words", BCP 14, RFC 8174,
              DOI 10.17487/RFC8174, May 2017.

16.2.  Informative References

   [BTSIG]    Bluetooth SIG, "Bluetooth Core Specification v5.3",
              July 2021.

   [SAMSUNG-HEALTH-SDK]
              Samsung Electronics, "Samsung Health Data SDK",
              https://developer.samsung.com/health/data/overview.html

   [JADX]     skylot, "JADX - Dex to Java decompiler",
              https://github.com/skylot/jadx


Appendix A.  Observed Wire Traces

   The following byte sequences were captured during a live session
   between a Galaxy Ring (SM-Q509) and Galaxy S24 Ultra.

A.1.  Device Configuration (Ring -> Host)

   Handle 0x0023, ATT Notification:

      a7 00 01 01 01 03 f2 01 f2 01 f2 01 0a 53 61 6d
      73 75 6e 67 20 45 6c 65 63 74 72 6f 6e 69 63 73
      ...
      15 47 61 6c 61 78 79 20 52 69 6e 67
      ...
      51 35 30 30

   Decoded fields:
      Manufacturer:  "Samsung Electronics"
      Device Name:   "Galaxy Ring"
      Model:         "Q500"

   A live read of the standard Generic Access Device Name characteristic
   (0x2A00) on the same unit returned "Galaxy Ring (NGYF)", where the
   parenthesised suffix is a per-device Bluetooth name token.  A live
   read of the Device Information Manufacturer Name characteristic
   (0x2A29) returned "Samsung Electronics", confirming the value above.

A.2.  Gesture Detection Sequence

   Ten gesture events captured with a 30-second gap between groups:

      Time          Handle   Payload
      ----          ------   -------
      19:37:56.193  0x0023   16 16 02 08 00 00 00
      19:37:57.851  0x0023   16 16 02 09 00 00 00
      19:37:59.382  0x0023   16 16 02 0a 00 00 00
      19:38:00.868  0x0023   16 16 02 0b 00 00 00
      19:38:02.313  0x0023   16 16 02 0c 00 00 00
      19:38:07.245  0x0023   16 16 02 0d 00 00 00
                    (30 second gap, no traffic)
      19:38:42.264  0x0023   16 16 02 0e 00 00 00
      19:38:46.260  0x0023   16 16 02 0f 00 00 00
      19:38:49.748  0x0023   16 16 02 10 00 00 00
      19:38:51.615  0x0023   16 16 02 11 00 00 00
      19:38:53.058  0x0023   16 16 02 12 00 00 00

A.3.  Configuration Key-Value Store (Ring -> Host)

   Handle 0x0023, ATT Notification, 0x14 prefix:

      Key          Value              Interpretation
      ---          -----              --------------
      RING0002     "12"               Ring size
      RING0004     "Q50XWWU2AZA4"    Firmware version
      RING0005     "5887"             Unknown identifier
      RING0009     "283"              Battery capacity (mAh)
      RING0012     "1 55 M 2"         Size/dimension metadata


Appendix B.  Live Validation (Second-Client Probe)

   This appendix records observations made in June 2026 by connecting a
   second BLE GATT client to the ring concurrently with the official
   Samsung application, and actively probing it (full service discovery,
   characteristic reads, command writes, and live notification capture).
   The ring (Bluetooth address D0:56:FB:71:B3:67) was bonded through the
   official app; the second client reused that bond.  The host was a
   Galaxy S24 Ultra (SM-S928B) running Android 16.

B.1.  Full GATT Database

   Service / Characteristic                       Properties
   ----------------------------------------       ----------
   1800 Generic Access
     2a00 Device Name                             Read
     2a01 Appearance                              Read
   1801 Generic Attribute
     2a05 Service Changed                         Indicate
   180a Device Information
     2a29 Manufacturer Name                       Read
   180d Heart Rate
     2a37 Heart Rate Measurement                  Notify
   1b1a Samsung FOTA
     797ae4e9...                                  Read, Notify
     63e30bad...                                  Write No Response
   79d34772-bc93-4360-8244-42b50a4a2b27
     2fb6a5aa...                                  Write, Notify
   1b1b Samsung DATA
     797ae4e9...                                  Read, Notify
     63e30bad...                                  Write No Response
   eedd5e73-6aa8-4673-8219-398a489da87c
     4ebe81f6...                                  Write, Notify
     a12be31c...                                  Read   (rolling, see B.8)
     50f98bfd...                                  Read   (rolling, see B.8)
   fff0
     fff1...                                      Notify
     fff2...                                      Write, Write No Response
   fd69
     4af351bb...                                  Read, Write, Indicate
     66aeea2f...                                  Read, Write, Indicate

   Characteristics 4af351bb and 66aeea2f each read back as a single 0x00
   octet.  The read-only pair a12be31c and 50f98bfd is NOT a static
   identifier as first supposed; it returns a fresh value on every read
   and is analysed in Appendix B.8.

B.2.  Characteristic Role Correction

   On characteristic 797ae4e9 (properties Read+Notify, 0x12) the ring
   delivered every observed notification -- gesture enable ACKs, pinch
   detections, the Channel 23 heartbeat, and Channel 11 battery reports.
   All host commands were written to 63e30bad (property Write No
   Response, 0x04) and were honoured.  This reverses the TX/RX role
   labels of Section 4.2 as originally published; the section has been
   corrected.

B.3.  Standard Heart Rate Service

   The ring exposes a standard Bluetooth Heart Rate Service (180d).  Its
   Heart Rate Measurement characteristic (2a37) emits notifications in
   the standard format, for example:

      06 4a 00 00 00 00

   Byte 0 (0x06) is the Flags field: HR value format = UINT8, sensor
   contact feature supported and contact detected.  Byte 1 (0x4a = 74)
   is the heart rate in beats per minute.  Any GATT client may subscribe
   to live heart rate this way, independent of the proprietary Channel
   10 health protocol.

B.4.  Second-Client Capability Matrix

   Connecting as a non-primary bonded client, the following operations
   were observed:

      Operation                                 Result
      ---------                                 ------
      Subscribe to notifications (all CCCDs)    Permitted
      Receive gesture events (Channel 22)       Broadcast to client
      Receive heartbeat (Channel 23)            Broadcast to client
      Receive heart rate (180d / 2a37)          Broadcast to client
      Enable/disable gestures (Ch 22)           Honoured and ACKed
      Battery sync request (0b 0b 03)           Serviced; report returned
      GATT characteristic reads                 Permitted
      Ring temperature query (Ch 11 MSG 5)      No response
      Cradle info query (Ch 11 MSG 4/6)         No response
      Debug channel queries (Channel 12)        No response

   The ring thus treats a secondary client as a first-class consumer of
   the notification stream and of gesture and battery messaging, but
   does not service diagnostic or auxiliary settings queries for it;
   those appear reserved to the primary (official-app) connection.

B.5.  Connection Parameters

   A request for the low-power connection priority (Android
   CONNECTION_PRIORITY_LOW_POWER, a longer connection interval) was
   accepted by the link.  Gesture-event and notification delivery
   continued normally at the relaxed interval.

B.6.  Channel Envelope Validation

   Writes omitting the doubled channel prefix produced no response: the
   single-octet 0x03 and the partial 0x0b 0x03 were silently ignored,
   while the correctly doubled 0x0b 0x0b 0x03 was serviced.  This
   confirms the two-octet channel envelope requirement of Section 6.3.

B.7.  Gesture Counter

   The gesture detection counter (Section 8.6, wire byte 3) was observed
   incrementing monotonically and persistently across a session, with
   values exceeding those in the original capture (up to 0x18 and
   beyond), consistent with a per-connection running counter.

B.8.  Rolling Challenge Characteristic (eedd5e73)

   The proprietary service eedd5e73-6aa8-4673-8219-398a489da87c contains
   two read-only characteristics, a12be31c and 50f98bfd, plus a third
   characteristic 4ebe81f6 with properties Write + Notify.

   The two read-only characteristics return a 16-octet value that is
   identical to each other at any given instant but is REGENERATED on
   every read.  Statistical analysis of consecutive reads showed:

      o  Each octet is bounded to the range 0..99 (0x00..0x63); no octet
         above 0x63 was ever observed.  The mean octet value was ~52.
      o  Within that range the octets are high-entropy: across 16 reads,
         most octet positions took a distinct value every time, with no
         correlation between consecutive reads.

   The per-read regeneration and lack of temporal continuity rule out a
   sensor stream (which would vary smoothly); the behaviour is that of a
   per-read NONCE.  Example consecutive reads (note complete change):

      read 1:  32 01 47 24 33 56 05 11 0a 3f 3c 55 48 2f 5b 4e
      read 2:  2a 16 2e 4e 36 12 5a 0d 13 30 62 10 14 49 04 30
      read 3:  51 24 11 1f 0e 05 35 58 16 1e 59 18 0e 48 18 37

   The full algorithm was subsequently recovered by static analysis of
   the SmartThings application (com.samsung.android.oneconnect), which
   implements this service as its generic BLE-accessory onboarding
   authentication (shared with Galaxy SmartTag).  The decompiled
   constants name the characteristics:

      eedd5e73   UUID_SERVICE_AUTHENTICATION
      a12be31c   UUID_CHAR_NONCE_NORMAL    (plaintext nonce; read above)
      4ebe81f6   UUID_CHAR_NONCE_ENCRYPT   (encrypted-nonce response)

   Authentication handshake (client = phone, proving knowledge of a
   per-device key to the ring):

      1.  Read a fresh 16-octet nonce N from a12be31c.
      2.  Derive a 16-octet AES key from the per-device key DK using a
          SHA-256-based KDF (single counter block, counter = 1):

             K = SHA-256( DK || 0x00000001 || "bleAuthentication" )[0:16]

      3.  Compute the response by AES-encrypting the fixed ASCII string
          "smartthings" under K, using the nonce N as the CBC IV:

             R = AES-128/CBC/PKCS5Padding( key = K, IV = N,
                                           plaintext = "smartthings" )

          (11-octet plaintext, PKCS5-padded to one 16-octet block.)
      4.  Write R (16 octets) to 4ebe81f6.  The ring validates R and, on
          success, marks the session established.

   The cipher specification (AES/CBC/PKCS5PADDING, 128-bit key), the
   literal strings "bleAuthentication" and "smartthings", and the KDF
   were all taken from decompiled code (classes aj.a "TagKeyManager",
   wb0.a, wb0.b, xb0.b, xb0.c).

   Key provenance: DK is the per-device "master secret" / Data
   Encryption Key (DEK).  Static analysis of the SmartThings key
   hierarchy (class aj.a "TagKeyManager" and the e2ee SharedKeyManager)
   shows DK is the root from which all per-device keys are derived by
   the same SHA-256 KDF with domain-separation labels:

      generateBleAuthenticationKey(DK) = b(DK, "bleAuthentication")
      generateCommandKey(DK, x)        = b(DK, x)
      generatePrivacyIdKey(DK)         = b(DK, "privacy")
      generateSigningKey(DK)           = b(DK, "signing")
      where b(s, label) = SHA-256( s || 0x00000001 || label )[0:16]

   DK itself is delivered to the phone as an encrypted DEK from the
   Samsung cloud and decrypted by the SmartThings end-to-end-encryption
   subsystem using a shared key held in the Android KeyStore (a
   hardware-backed, non-exportable key).  The root of trust is therefore
   the device KeyStore.

   Security note: the construction is sound.  The nonce/IV is public and
   the plaintext is constant, but the AES key derives from DK, whose own
   root of trust is a hardware-backed KeyStore key.  DK is not present in
   any companion application in plaintext, is not brute-forceable
   (128-bit, per-device), and was not recoverable by this analysis; a
   valid response cannot be forged without it.  This protocol was
   characterised and its algorithm fully reconstructed -- from the
   on-wire nonce down to the KeyStore root of trust -- but it was NOT
   defeated, nor is it defeatable without compromising the hardware
   KeyStore.

B.9.  Wearing-State Detection

   Removing the ring from the finger and replacing it produced the
   following two Channel 11 frames, confirming Section 7.5 exactly:

      0b 0b 34 37 00      removed   (MSG 52 = 0x34, param 55 = 0x37, 0)
      0b 0b 34 37 01      on-finger (MSG 52 = 0x34, param 55 = 0x37, 1)

   Two further observations accompanied the transition:

      o  While the ring was off the finger, the standard Heart Rate
         Measurement characteristic (2a37) reported 0 bpm (loss of skin
         contact), and resumed a non-zero value once replaced.
      o  Removal caused the Samsung Health companion to tear down its
         ring wearable session ("session onDisConnected ... TIME_OUT"),
         but the underlying BLE link remained connected; wearing state
         is therefore an application-layer signal independent of the
         GATT connection.

B.10.  Channel 23 Heartbeat Cadence

   Two consecutive Channel 23 heartbeat frames (17 17 01 03 03) were
   observed exactly 600 seconds apart, confirming the "approximately
   every 10 minutes" cadence noted in Section 6.3 to be precisely ten
   minutes on this firmware.

B.11.  Spurious Gesture Detections

   During handling -- including removing and replacing the ring -- the
   ring emitted numerous Channel 22 pinch-detection frames (Section 8.5)
   not corresponding to deliberate double-pinches.  Implementations that
   act on gesture events SHOULD expect false positives from incidental
   finger and hand movement, and may wish to debounce or require
   application context before acting.

B.12.  Secure Command Channel (SmartThings BLE-Accessory Layer)

   Beyond the one-way nonce authentication of B.8, the SmartThings code
   (classes EncryptManager / pc0.d / aj.a) implements a full MUTUAL
   authentication and an encrypted command channel over this service.
   This is the generic SmartThings BLE-accessory secure-control protocol
   (used for features such as Find-My-style control), distinct from the
   SAP gesture/health channels of the 1b1b Data Service.  All keys derive
   from the per-device master secret DK via the single KDF of B.8.

   Phase 1 -- Mutual authentication.  Each party proves possession of DK
   by AES-encrypting the constant "smartthings" under the BLE auth key
   K_auth = SHA-256(DK || 0x00000001 || "bleAuthentication")[0:16], using
   the OTHER party's 16-octet nonce as the CBC IV:

      Client: read device nonce N_D; write
              AES-CBC( K_auth, IV = N_D, "smartthings" )  to NONCE_ENCRYPT
      Device: send its nonce N_D and
              AES-CBC( K_auth, IV = N_E, "smartthings" );
              client decrypts with the client nonce N_E and verifies the
              plaintext equals "smartthings".

   N_E is a fresh client nonce (SecureRandom).  The connection is dropped
   if N_D == N_E (anti-reflection).

   Phase 2 -- Encrypted command channel.  Once secured, both directions
   use a command key bound to the device nonce:

      K_cmd = SHA-256( DK || 0x00000001 || N_D )[0:16]
      app->device:  AES-CBC( K_cmd, IV = N_D, LE32(counter) || command )
      device->app:  AES-CBC decrypt -> LE32(counter) || payload

   A 4-octet little-endian counter is prepended to every message and
   increments per message, giving ordering and replay protection.  Cipher
   throughout is AES-128/CBC/PKCS5Padding.

   Phase 3 -- Integrity and freshness.

      Signature:  the low 4 octets of AES-CBC( K_sign, IV = per-device,
                  data[0:16] ), a 4-octet CBC-MAC, where
                  K_sign = SHA-256(DK || 0x00000001 || "signing")[0:16].
      Aging:      a time counter (serverTime - 1593648000) / 900 -- i.e.
                  15-minute windows from 2020-07-02 -- with +/-1 window
                  tolerance, rejecting stale or replayed commands beyond
                  roughly 15 minutes of clock skew.

   The full key hierarchy, all rooted in DK (itself rooted in the device
   KeyStore, B.8):

      DK --+-- || "bleAuthentication" --> mutual-auth key (B.8/B.12-P1)
           +-- || N_D                  --> command key      (B.12-P2)
           +-- || "signing"            --> signing key      (B.12-P3)
           +-- || "privacy"            --> privacy-ID key   (BLE MAC rotation)

   The design is sound: mutual authentication, per-session command keys
   bound to a device nonce, monotonic-counter replay protection, a CBC-MAC
   integrity tag, and time-window freshness -- with all key material
   derived from a per-device secret whose root of trust is hardware-backed.


Authors' Address

   Nikita Viventsov
   Independent Security Research
