@echo off
REM 建一个竖屏手机 AVD（android-28 default x86，和 TV 那个同镜像）
set ANDROID_AVD_HOME=D:\Android\avd
set ANDROID_SDK_ROOT=D:\Android\sdk
echo no | D:\Android\sdk\cmdline-tools\latest\bin\avdmanager.bat create avd -n phone28 -k "system-images;android-28;default;x86" -d pixel_4 --force
echo --- 列表 ---
D:\Android\sdk\cmdline-tools\latest\bin\avdmanager.bat list avd
