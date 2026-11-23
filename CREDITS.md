# Credits

ASoftware iOS is built on other people's work, and a good part of what got fixed was found by the
people who use it. This page names both. It covers the Android app (this repository) and the Windows
version ([asoft-ios](https://github.com/alpersamur3/asoft-ios)). The licenses are in the
[README](README.md#licenses).

## Built on

| Project | By | What it is here |
| --- | --- | --- |
| [QEMU](https://github.com/qemu/qemu) | the QEMU project | The machine iOS runs in. We build it from our fork with our patches. |
| [qemu-sptm](https://github.com/jprx/qemu-sptm) | jprx | QEMU with Apple's SPTM, TXM and GXF: the machine iOS 26 and watchOS boot on, and the base of our fork. |
| [darwin-vm](https://github.com/jprx/darwin-vm) | jprx | Its sysroot (a shell and file tools) runs the disk steps of the iOS 26 and watchOS setup. |
| [ios-sweet](https://github.com/dipa-studio/ios-sweet) | dipa-studio | The XNU port iOS 16 boots on. Our iOS 16 kernel patches and the Windows version descend from it. |
| [XNU](https://github.com/apple-oss-distributions/xnu) and [hfs](https://github.com/apple-oss-distributions/hfs) | Apple, open source | The iOS 16 kernel is XNU 8796 built with ios-sweet's patches and ours. |
| [darwin-xnu-build](https://github.com/blacktop/darwin-xnu-build) | blacktop | The build scripts our CI compiles that kernel with. |
| [reims-vgpu](https://github.com/steelbrain/reims-vgpu) and [metal2vulkan](https://github.com/steelbrain/metal2vulkan) | steelbrain | The host side of Apple's paravirtual GPU and the Metal to Vulkan translator. Our Android and Windows ports of both are linked into QEMU. |
| [Mesa Turnip](https://gitlab.freedesktop.org/mesa/mesa) | the Mesa project | The Vulkan driver the app brings for Adreno GPUs, built with our patches. |
| [vphone-cli](https://github.com/Lakr233/vphone-cli) | Lakr233 | Its GraphicLoader, the Metal compiler plugin inside iOS 26, built by our CI with one patch. GraphicLoader is based on [0xjohnnydev](https://github.com/0xjohnnydev/0xjohnnydev.github.io)'s clean-room metal-patch. |
| [ipsw](https://github.com/blacktop/ipsw) | blacktop | The Windows installer uses it, and the app reads the key list it carries when Apple's key service does not answer. |
| [apfs-fuse](https://github.com/sgan81/apfs-fuse) | sgan81 | Mounts Apple's disk images in the small helper VM that builds the disks during setup. |
| Linux 6.12 and Ubuntu 24.04 packages | kernel.org, Ubuntu | That helper VM. |
| [Termux packages](https://github.com/termux/termux-packages) | Termux | The libraries QEMU links on Android. On Windows the same job is done by MSYS2's packages. |
| [lzfse](https://github.com/lzfse/lzfse), [Conscrypt](https://github.com/google/conscrypt), the [Vulkan loader](https://github.com/KhronosGroup/Vulkan-Loader) | Apple, Google, Khronos | Decoding Apple's archives during setup, TLS 1.3 for Wireless debugging pairing, and loading Turnip. |

## Learned from

Read while building this. They showed the way more than once.

- [vphone-cli](https://github.com/Lakr233/vphone-cli) (Lakr233): the research on Apple's vphone600
  guest, from Setup Assistant and activation to the guest's GPU driver.
- [orchard](https://github.com/yaelliethy/orchard) (yaelliethy): an Apple arm64 guest on an x86 host
  under TCG, with reims drawing on the host's Vulkan GPU.
- [MAXZVER's reims-vgpu fork](https://github.com/MAXZVER/reims-vgpu): a Windows port of reims before
  ours, and what it took to share the guest's memory with the GPU there.
- [OpenSEP](https://github.com/owngoal-dev/OpenSEP) and OpenAVB (owngoal-dev): what the vresearch1
  platform expects from its host.
- [vphone-linux](https://github.com/YahiaAngelo/vphone-linux) (YahiaAngelo): the same guest and GPU
  stack on Linux, and its route to the GPU driver.
- [super-tart-vphone-writeup](https://github.com/wh1te4ever/super-tart-vphone-writeup) (wh1te4ever):
  why iOS 26 stays on a black setup screen without the Metal driver.
- AOSP's adb and BoringSSL: the app's own adb client is written after them.
- [termux-app](https://github.com/termux/termux-app)'s documentation of Android's child process
  limit.

## People

Listed here: a patch, a test build run and reported back, or a report that a release's notes or a
fix's commit names. Names are GitHub logins. Everyone who sent a "works" report is behind the
[tested phones list](DEVICES.md).

### Patches

- [Hubitski](https://github.com/Hubitski): setup logs at any time, the memory sheet, keep the screen
  on, the thermal text (#184, in 0.1.7).
- fasilfaezi: ran iOS 26 on a Mali GPU with our engine and sent what it took, and the cache
  maintenance for the GPU check on such GPUs (#82, in 0.1.6).
- [ItzPriyatanuDas](https://github.com/ItzPriyatanuDas): found that AMD's Vulkan driver needs one
  extension hidden. The fix in the Windows version 0.3.0 is that diagnosis (asoft-ios #2).

### Test builds

Ran a test build on their own phone and sent the result back:
[Pronian](https://github.com/Pronian) (#96), [ARDACD](https://github.com/ARDACD) (#148),
[DarkXonline](https://github.com/DarkXonline) (#37).

### Reports that led to a fix

| What changed | In | Reported by |
| --- | --- | --- |
| Compatibility mode for Adreno 740 and 750 | 0.1.4 | senna1992 (#1) |
| First boot on CPUs without SSBS | 0.1.6 | adamk324 (#18), appleckae2510 (#42), fu893703-droid (#57) |
| A setup boot that ended with "guest did not answer sync" | 0.1.6 | ArturGG (#19), DarkXonline (#37), Dinsuljevic336 (#50) |
| iOS 26 running out of memory mappings in DataMigration | 0.1.6 | jameskeller08 (#6), mihail3123 (#10) |
| The memory question before a start | 0.1.6 | fars20092020 (#8), Raj3EAH (#17) |
| A render pipeline format iOS 26 could not create | 0.1.6 | Majed487 (#11) |
| iOS 16 on Android 10 | 0.1.6 | SharkZubat (#23) |
| iOS 16's first start giving up after two hours | 0.1.6 | lewalexzy (#59) |
| A notification Android refuses taking the setup down with it | 0.1.6 | Jabjubinski (#43) |
| Android closing the app for memory during a disk build | 0.1.6 | Jay-Jayismyname1 (#67) |
| A setup held as "hot" that was the battery rule | 0.1.6 | LukeGTH (#63) |
| Crash reports that name the line that matters | 0.1.6 | youssefbenagida-star (#53), wim69573-gif (#58) |
| Adreno 825 in the GPU table | 0.1.6 | StealthTensor (#4) |
| Adreno 600-series phones on older Android kernels | 0.1.7 | ARDACD (#148), nateamog-oss (#281) |
| Newer Malis stopping with "unsupported HostMemory" | 0.1.7 | Pronian (#96), Ricca665 (#167), unique2003228 (#275), igordengi68-a11y (#277) |
| Resume after an interrupted decrypt, and waiting for heat during it | 0.1.7 | 7ptcm7s59v-netizen (#247, #258, #261) |
| Settings freezing while wireless adb connects | 0.1.7 | Ajbevan-create (#234), patchon-t (#297) |
| The setup console typing over itself in DataMigration | 0.1.7 | be3uchii (#259), pegioos (#284) |
| The GPU check's mode lost on an app update | 0.1.7 | fandroid7 (#296) |
| "Apple's server not found" now names Private DNS and VPN | 0.1.7 | jeanblouloud2003-crypto (#262, #263) |
| A tap cancelling the automatic wake | 0.1.7 | SuperMarioYAHOOYAHOO2 (#95), pegiooos (#122) |
| iOS 16 closed by Android on phones with 8 GB or less: 2 GB by default | 0.1.7 | pranayraikwar19-hub (#89), vvautosalescrm-design (#104), withmast49-afk (#115), mariobg099-svg (#164) |
| What a failed iOS 16 first start records | 0.1.7 | pushstorm (#100), TheGuyWhoIsIdiot (#163) |
| A helper job killed for memory is told as that | 0.1.7 | ql249998-create (#282) |
| Picking a file that is not a setup bundle | 0.1.7 | stena11092010-jpg (#171), dungmaxl123-cmd (#289), manaloken44-dot (#290), baoanf11-pixel (#293) |
| A paused download is not an error; the setup's own run is off the Save and Turn off buttons | 0.1.7 | Talibbaidar (#155, #172) |
| A crash on Unpair | 0.1.7 | Zetbus (#110) |
| Windows: a stop when the GPU is lost | 0.3.1 | senna1992 (asoft-ios #12) |
| Windows: WSL without a distro counts as installed | 0.3.1 | stormerxd (asoft-ios #6), cchunk357 (asoft-ios #11) |
| Windows: the disk check repeats until it is clean | 0.3.1 | Superedsyt (asoft-ios #13) |
| Windows: visionOS's first boot waits for the folder its next step needs | 0.3.1 | windowsglass44 (asoft-ios #10) |

Missing, or want your name changed or taken off? Open an issue, it is a one-line edit.
