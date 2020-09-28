# The ARMv7 is significanly faster due to the use of the hardware FPU
APP_ABI := armeabi-v7a arm64-v8a x86
# APP_ABI :=  armeabi-v7a arm64-v8a
APP_PLATFORM := android-16

ifneq ($(APP_OPTIM),debug)
  APP_CFLAGS += -O3 -fPIC
endif
APP_CFLAGS += -fPIC
