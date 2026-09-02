#!/usr/bin/env bash
set -euo pipefail

# These checks guarantee the exact WebP files supplied for MobileTina are committed
# byte-for-byte. No conversion, resize, or recompression is allowed for app artwork.
sha256sum -c <<'EOF'
0556978fd6eb7a20b1e23d355ad70ceeb5011600dab7ee13cad05a5204a2ed7e  V2rayNG/app/src/main/res/drawable-nodpi/auto.webp
e13ee902042ab5e560ee71744db016fea2404f9b5d6c4b664cdd05f1187bfc75  V2rayNG/app/src/main/res/drawable-nodpi/blue.webp
dd42c60232c45e87cb009b1f41959f785958e0f27e180b89be46ff86359fe494  V2rayNG/app/src/main/res/drawable-nodpi/fab.webp
b93ea494eb517d9fe940e7780dd2eab58ed5fd3994f6dd876114b8a4db4039c8  V2rayNG/app/src/main/res/drawable-nodpi/nav.webp
a24baa9ccb77c06453f9864634974b769879eeb42bb9a87908971a9713e6bf28  V2rayNG/app/src/main/res/drawable-nodpi/red.webp
87ec504cdc3c7ac3287e32140f3a5649df8d2162e7312e752a8748cc50831c2e  V2rayNG/app/src/main/res/drawable-nodpi/stop.webp
ff4c08b78bca9a1654f918f9b89813be90a5966d65805cc419b5b1482a75b5d5  V2rayNG/app/src/main/res/drawable-nodpi/white.webp
843afec129b2bbf6ed205854a78d41719054bb3905d706c73ba9e96b6451b6e5  V2rayNG/app/src/main/res/drawable-nodpi/yellow.webp
4530725a0c14798ec265738d35e8b0409187377b695e716eaff2575d35cde1c2  V2rayNG/app/src/main/res/mipmap-nodpi/icon.webp
4530725a0c14798ec265738d35e8b0409187377b695e716eaff2575d35cde1c2  V2rayNG/app/src/main/res/mipmap-nodpi/ic_launcher_foreground.webp
EOF

if find V2rayNG/app/src/main/res -type f \( \
    -name 'mt_auto_*' -o \
    -name 'mt_manual_*' -o \
    -name 'mt_nav.*' -o \
    -name 'ic_launcher.png' -o \
    -name 'ic_launcher_round.png' -o \
    -name 'ic_launcher_foreground.png' \
\) -print -quit | grep -q .; then
    echo 'Legacy MobileTina artwork still exists in Android resources' >&2
    exit 1
fi
