LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := han1me_ech
LOCAL_SRC_FILES := native-lib.cpp
LOCAL_CPP_FEATURES := rtti exceptions
LOCAL_CPPFLAGS := -std=c++20
LOCAL_LDLIBS := -llog -lz
LOCAL_STATIC_LIBRARIES := curl ssl crypto

include $(BUILD_SHARED_LIBRARY)

# Prebuilt static libs (will be built by ndk-build from source)
$(call import-module,boringssl)
$(call import-module,curl)
