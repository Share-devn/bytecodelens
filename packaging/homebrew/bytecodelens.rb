# Homebrew formula for BytecodeLens.
#
# This is the "CLI" formula — installs the fat jar + a wrapper script.
# Users run `bytecodelens` in their terminal (CLI) or launch the GUI directly.
#
# For the `--cask` style install that drops a .app into /Applications, see the
# separate cask/bytecodelens.rb file (requires an Apple-notarized .dmg).
#
# Refresh procedure on a new release:
#   1. Bump `version` to match the GitHub tag.
#   2. Refresh `sha256`:
#        curl -sL \
#          "https://github.com/Share-devn/bytecodelens/releases/download/v#{version}/BytecodeLens-#{version}-all.jar" \
#          | shasum -a 256
#   3. Commit to the tap repo (e.g. Share-devn/homebrew-bytecodelens).

class Bytecodelens < Formula
  desc "Java RE cockpit — decompile, attach, diff, patch"
  homepage "https://github.com/Share-devn/bytecodelens"
  version "1.0.0"
  url "https://github.com/Share-devn/bytecodelens/releases/download/v#{version}/BytecodeLens-#{version}-all.jar"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install "BytecodeLens-#{version}-all.jar" => "bytecodelens.jar"
    bin.write_jar_script libexec/"bytecodelens.jar", "bytecodelens", java_version: "21"
  end

  test do
    system bin/"bytecodelens", "help"
  end
end
