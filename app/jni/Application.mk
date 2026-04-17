APP_OPTIM := release
APP_PLATFORM := android-28
# APP_ABI is controlled by Gradle's abiFilters
APP_CFLAGS := -O3 -DPKGNAME=hev/htp
APP_CPPFLAGS := -O3 -std=c++11
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
