cask "drift-studio" do
  version "@version@"
  sha256 "@sha256:macos-arm64.dmg@"

  url "https://github.com/gmitch215/Drift/releases/download/v#{version}/drift-#{version}-macos-arm64.dmg"
  name "Drift Studio"
  desc "Explore how Kotlin behaves across platforms"
  homepage "https://github.com/gmitch215/Drift"

  livecheck do
    url :url
    strategy :github_latest
  end

  depends_on arch: :arm64
  depends_on :macos

  app "Drift.app"

  postflight_steps do
    run "/usr/bin/xattr", args: ["-dr", "com.apple.quarantine", "{{appdir}}/Drift.app"]
  end

  zap trash: [
    "~/Library/Caches/dev.gmitch215.drift",
    "~/Library/Preferences/dev.gmitch215.drift.plist",
    "~/Library/Saved Application State/dev.gmitch215.drift.savedState",
  ]
end
