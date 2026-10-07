#!/bin/sh
set -eu

app=$1
out=$2
tool=${APPIMAGETOOL:-appimagetool}
arch=${ARCH:-$(uname -m)}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
dir=$work/Drift.AppDir

mkdir -p "$dir/usr"
cp -R "$app/bin" "$app/lib" "$dir/usr/"
cp "$app/lib/Drift.png" "$dir/drift.png"

cat > "$dir/drift.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Drift
Comment=Drift Studio: explore how Kotlin behaves across platforms
Exec=Drift %f
Icon=drift
Categories=Development;
MimeType=application/x-driftcase;
Terminal=false
DESKTOP

cat > "$dir/AppRun" <<'RUN'
#!/bin/sh
here=$(dirname "$(readlink -f "$0")")
exec "$here/usr/bin/Drift" "$@"
RUN
chmod 755 "$dir/AppRun"

mkdir -p "$(dirname "$out")"
ARCH=$arch "$tool" --appimage-extract-and-run "$dir" "$out"
