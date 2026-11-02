@echo off
set ANDROID_AVD_HOME=D:\Androidvd
set ANDROID_SDK_ROOT=D:\Android\sdk
echo no| D:\Android\sdk\cmdline-tools\latestinvdmanager.bat create avd -n phone28 -k "system-images;android-28;default;x86" -d pixel_4 --force
