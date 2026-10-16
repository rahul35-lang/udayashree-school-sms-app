#!/bin/bash
set -e

APK="/home/rahul/Desktop/SchoolSMSGateway.apk"
if [ ! -f "$APK" ]; then
    APK="/home/rahul/SchoolSMSGateway/android/SchoolSMSGateway.apk"
fi

echo "=========================================================="
echo "   School SMS Gateway - Phone Auto-Installer"
echo "=========================================================="
echo "Target APK: $APK"
echo ""

echo "Checking connected device..."
DEVICES=$(adb devices | grep -v "List" | grep "device$" | awk '{print $1}')

if [ -z "$DEVICES" ]; then
    echo "⚠️  No authorized Android device detected yet."
    echo "Please ensure:"
    echo " 1. Your phone is connected via USB cable."
    echo " 2. Developer Options -> 'USB Debugging' is ENABLED."
    echo " 3. Phone screen is UNLOCKED and tap 'Allow USB Debugging' if prompted."
    echo ""
    echo "Waiting for device to connect (Ctrl+C to cancel)..."
    adb wait-for-device
    sleep 1
fi

DEVICE_ID=$(adb devices | grep -v "List" | grep "device$" | head -n 1 | awk '{print $1}')
echo "✓ Device detected: $DEVICE_ID"

echo "Installing School SMS Gateway..."
adb -s "$DEVICE_ID" install -r -d "$APK"

echo "Granting autonomous permissions..."
adb -s "$DEVICE_ID" shell pm grant com.school.smsgateway android.permission.SEND_SMS 2>/dev/null || true
adb -s "$DEVICE_ID" shell pm grant com.school.smsgateway android.permission.READ_PHONE_STATE 2>/dev/null || true
adb -s "$DEVICE_ID" shell pm grant com.school.smsgateway android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo "Launching School SMS Gateway..."
adb -s "$DEVICE_ID" shell am start -n com.school.smsgateway/.MainActivity

echo ""
echo "=========================================================="
echo "✓ Installation & Setup Completed Successfully!"
echo "=========================================================="
