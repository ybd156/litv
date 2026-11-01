#!/usr/bin/env bash
# =============================================================================
# 修复免 root JDK 的 cacerts 信任库
#
# 问题：apt 的 openjdk deb 里 lib/security/cacerts 是指向
#       /etc/ssl/certs/java/cacerts 的**绝对符号链接**（由 ca-certificates-java
#       包在安装时生成）。我们用 dpkg-deb -x 解包到用户目录，这个链接就断了。
#       后果：Java 里所有 HTTPS 都抛 SSLHandshakeException，
#            表现成 Gradle "could not resolve plugin"（完全看不出是证书问题）。
#
# 解决：用系统自带的 PEM 根证书包 /etc/ssl/certs/ca-certificates.crt，
#       逐张导入成一个新的 PKCS12 信任库，替换掉断链。
# =============================================================================
set -uo pipefail

ROOT="$HOME/android-dev"
J="$ROOT/jdkpkg/usr/lib/jvm/java-21-openjdk-amd64"
KEYTOOL="$J/bin/keytool"
CACERTS="$J/lib/security/cacerts"
PEM="/etc/ssl/certs/ca-certificates.crt"

say() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
ok()  { printf '\033[32m[ok]\033[0m %s\n' "$*"; }
err() { printf '\033[31m[err]\033[0m %s\n' "$*"; }

[ -x "$KEYTOOL" ] || { err "找不到 keytool: $KEYTOOL"; exit 1; }
[ -f "$PEM" ] || { err "找不到系统根证书包: $PEM"; exit 1; }

# 已经修过就跳过（是普通文件且够大）
if [ -f "$CACERTS" ] && [ ! -L "$CACERTS" ] && [ "$(stat -c %s "$CACERTS")" -gt 10000 ]; then
  ok "cacerts 已修复（$(stat -c %s "$CACERTS") 字节）"
  exit 0
fi

say "拆分母证书包"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 按 BEGIN/END 切分
awk -v dir="$TMP" '
  /-----BEGIN CERTIFICATE-----/ { n++; f=sprintf("%s/c%04d.pem", dir, n) }
  f { print > f }
  /-----END CERTIFICATE-----/ { close(f); f="" }
' "$PEM"

count=$(find "$TMP" -name 'c*.pem' | wc -l)
[ "$count" -gt 0 ] || { err "没拆出证书"; exit 1; }
say "共 $count 张证书，开始导入"

rm -f "$CACERTS"
i=0
for f in "$TMP"/c*.pem; do
  i=$((i + 1))
  "$KEYTOOL" -importcert -noprompt -storetype PKCS12 \
    -keystore "$CACERTS" -storepass changeit \
    -alias "ca$i" -file "$f" >/dev/null 2>&1 || true
done

if [ -s "$CACERTS" ]; then
  ok "cacerts 重建完成（$(stat -c %s "$CACERTS") 字节，导入 $i 张）"
  "$KEYTOOL" -list -keystore "$CACERTS" -storepass changeit 2>/dev/null | grep -c "trustedCertEntry" | \
    xargs -I{} echo "  信任库中有效证书数: {}"
else
  err "cacerts 重建失败"
  exit 1
fi

# 顺便把 JDK 里其它断掉的绝对链接也指回包内
say "修复其它断链"
find "$J" -type l 2>/dev/null | while read -r lnk; do
  [ -e "$lnk" ] && continue
  tgt="$(readlink "$lnk")"
  case "$tgt" in
    /*) : ;;                 # 绝对链接：下面按文件名找
    *)  continue ;;          # 相对链接如果断了，说明本来就没这个文件，跳过
  esac
  base="$(basename "$tgt")"
  found="$(find "$ROOT/jdkpkg" -name "$base" -type f 2>/dev/null | head -n1)"
  [ -n "$found" ] && rm -f "$lnk" && cp -f "$found" "$lnk"
done
ok "断链处理完成"

say "验证 HTTPS"
cat > /tmp/HttpsTest.java <<'JAVA'
public class HttpsTest {
  public static void main(String[] a) throws Exception {
    var u = new java.net.URL("https://maven.aliyun.com/repository/public/");
    var c = (java.net.HttpURLConnection) u.openConnection();
    c.setConnectTimeout(15000); c.setReadTimeout(15000);
    System.out.println("HTTP " + c.getResponseCode());
  }
}
JAVA
cd /tmp && "$J/bin/java" /tmp/HttpsTest.java 2>&1 | tail -3

echo "CACERTS_FIXED"
