#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_TOOLS="/home/rahul/Android/Sdk/build-tools/36.0.0"
PLATFORM="/home/rahul/Android/Sdk/platforms/android-37.0"
APP_DIR="$DIR/app/src/main"
BUILD_DIR="/tmp/school_sms_build"
OUTPUT_APK="/home/rahul/Desktop/SchoolSMSGateway.apk"

echo "=========================================================="
echo "   Building School SMS Gateway Android APK"
echo "=========================================================="

echo "=== 1. Preparing Directories ==="
rm -rf $BUILD_DIR
mkdir -p $BUILD_DIR/res_compiled $BUILD_DIR/gen $BUILD_DIR/classes $BUILD_DIR/dex $BUILD_DIR/out

echo "=== 2. Compiling Resources with AAPT2 ==="
$BUILD_TOOLS/aapt2 compile --dir $APP_DIR/res -o $BUILD_DIR/res_compiled/res.zip

echo "=== 3. Linking Resources and Generating R.java ==="
$BUILD_TOOLS/aapt2 link -I $PLATFORM/android.jar \
    --manifest $APP_DIR/AndroidManifest.xml \
    --java $BUILD_DIR/gen \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    --version-code 1 \
    --version-name "1.0.0" \
    -o $BUILD_DIR/out/base.apk \
    --auto-add-overlay \
    $BUILD_DIR/res_compiled/res.zip

echo "=== 4. Compiling Java Sources ==="
javac -cp $PLATFORM/android.jar:$BUILD_DIR/gen \
    -d $BUILD_DIR/classes \
    -source 17 -target 17 \
    $BUILD_DIR/gen/com/school/smsgateway/R.java \
    $(find $APP_DIR/java -name "*.java")

echo "=== 5. Dexing Classes with D8 ==="
$BUILD_TOOLS/d8 \
    --lib $PLATFORM/android.jar \
    --output $BUILD_DIR/dex \
    --min-api 26 \
    $(find $BUILD_DIR/classes -name "*.class")

echo "=== 6. Packaging APK ==="
cp $BUILD_DIR/out/base.apk $BUILD_DIR/out/unaligned.apk
(cd $BUILD_DIR/dex && zip -u -q $BUILD_DIR/out/unaligned.apk classes.dex)

echo "=== 7. Zipaligning APK ==="
$BUILD_TOOLS/zipalign -f -p 4 $BUILD_DIR/out/unaligned.apk $BUILD_DIR/out/aligned.apk

echo "=== 8. Signing APK ==="
KEYSTORE_PATH="/home/rahul/SchoolSMSGateway/gateway.keystore"
if [ ! -f "$KEYSTORE_PATH" ]; then
    keytool -genkey -v -keystore "$KEYSTORE_PATH" \
        -alias gateway_key -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass gateway123 -keypass gateway123 \
        -dname "CN=School SMS Gateway,O=School,C=NP"
fi

$BUILD_TOOLS/apksigner sign \
    --ks "$KEYSTORE_PATH" \
    --ks-key-alias gateway_key \
    --ks-pass pass:gateway123 \
    --key-pass pass:gateway123 \
    --out "$OUTPUT_APK" \
    $BUILD_DIR/out/aligned.apk

cp "$OUTPUT_APK" "$DIR/SchoolSMSGateway.apk"

echo "=== 9. Verifying APK Signature ==="
$BUILD_TOOLS/apksigner verify "$OUTPUT_APK"

echo ""
echo "=========================================================="
echo "✓ BUILD SUCCESSFUL!"
echo "APK Generated at: $OUTPUT_APK"
echo "Size: $(du -h "$OUTPUT_APK" | cut -f1)"
echo "=========================================================="
