# vcpkg-android-wrapper.cmake
# This file is used as CMAKE_TOOLCHAIN_FILE in Gradle.

# 1. Handle VCPKG_ROOT
if(NOT DEFINED VCPKG_ROOT OR VCPKG_ROOT STREQUAL "")
  if(NOT "$ENV{VCPKG_ROOT}" STREQUAL "")
    set(VCPKG_ROOT "$ENV{VCPKG_ROOT}")
  endif()
endif()

# 2. Determine NDK path
if(NOT DEFINED HOLDER_ANDROID_NDK_HOME OR HOLDER_ANDROID_NDK_HOME STREQUAL "")
  if(NOT "$ENV{ANDROID_NDK_HOME}" STREQUAL "")
    set(HOLDER_ANDROID_NDK_HOME "$ENV{ANDROID_NDK_HOME}")
  elseif(NOT "$ENV{ANDROID_NDK_ROOT}" STREQUAL "")
    set(HOLDER_ANDROID_NDK_HOME "$ENV{ANDROID_NDK_ROOT}")
  elseif(DEFINED ANDROID_NDK)
    set(HOLDER_ANDROID_NDK_HOME "${ANDROID_NDK}")
  endif()
endif()

# 3. Include the official Android NDK toolchain
# This ensures that CMAKE_SYSROOT and other Android variables are set correctly for AGP.
set(_ANDROID_TOOLCHAIN_FILE "${HOLDER_ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")
if(EXISTS "${_ANDROID_TOOLCHAIN_FILE}")
  include("${_ANDROID_TOOLCHAIN_FILE}")
else()
  # Only fail in the main project if NDK is missing
  if(NOT CMAKE_BINARY_DIR MATCHES "CMakeTmp")
     message(FATAL_ERROR "Android NDK toolchain not found at ${_ANDROID_TOOLCHAIN_FILE}. Please check HOLDER_ANDROID_NDK_HOME.")
  endif()
endif()

# 4. Integrate vcpkg
if(DEFINED VCPKG_ROOT AND NOT VCPKG_ROOT STREQUAL "")
  if(DEFINED HOLDER_ANDROID_NDK_HOME)
    set(ENV{ANDROID_NDK_HOME} "${HOLDER_ANDROID_NDK_HOME}")
    set(ENV{ANDROID_NDK_ROOT} "${HOLDER_ANDROID_NDK_HOME}")
  endif()

  # When including vcpkg.cmake after a toolchain is already loaded,
  # it acts as a supplementary script to set up library paths.
  include("${VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake")
else()
  if(NOT CMAKE_BINARY_DIR MATCHES "CMakeTmp")
    message(WARNING "VCPKG_ROOT is not set. vcpkg libraries will not be available.")
  endif()
endif()
