class Drift < Formula
  desc "Diagnose why a program passes in one environment and fails in another"
  homepage "https://github.com/gmitch215/Drift"
  license "MIT"

  livecheck do
    url :stable
    strategy :github_latest
  end

  on_macos do
    depends_on arch: :arm64

    on_arm do
      url "https://github.com/gmitch215/Drift/releases/download/v@version@/drift-@version@-macos-arm64.tar.gz"
      sha256 "@sha256:macos-arm64.tar.gz@"
    end
  end

  on_linux do
    on_intel do
      url "https://github.com/gmitch215/Drift/releases/download/v@version@/drift-@version@-linux-x64.tar.gz"
      sha256 "@sha256:linux-x64.tar.gz@"
    end
    on_arm do
      url "https://github.com/gmitch215/Drift/releases/download/v@version@/drift-@version@-linux-arm64.tar.gz"
      sha256 "@sha256:linux-arm64.tar.gz@"
    end
  end

  def install
    bin.install "drift"
  end

  test do
    assert_match "drift version #{version}", shell_output("#{bin}/drift --version")

    capsule = <<~JSON
      {"attributes":[{"path":"jdk.version","source":"java","stability":"static","value":"%s"}],"label":"%s","probes":[],"schema":1}
    JSON
    (testpath/"green.json").write format(capsule, "17", "green")
    (testpath/"red.json").write format(capsule, "21", "red")

    diagnosis = shell_output("#{bin}/drift diagnose green.json red.json")
    assert_match "\"after\":\"21\",\"before\":\"17\"", diagnosis
    assert_match "\"path\":\"jdk.version\"", diagnosis
  end
end
