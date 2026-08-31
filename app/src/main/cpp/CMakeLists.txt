cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(HOLDER_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../submodules/holder-core")
include("${HOLDER_CORE_DIR}/cmake/HolderVersion.cmake")

if(NOT DEFINED VCPKG_TARGET_TRIPLET AND (DEFINED ANDROID_ABI OR DEFINED CMAKE_ANDROID_ARCH_ABI))
  if(DEFINED ANDROID_ABI)
    set(_holder_android_abi "${ANDROID_ABI}")
  else()
    set(_holder_android_abi "${CMAKE_ANDROID_ARCH_ABI}")
  endif()

  if(_holder_android_abi STREQUAL "arm64-v8a")
    set(VCPKG_TARGET_TRIPLET "arm64-android" CACHE STRING "" FORCE)
  elseif(_holder_android_abi STREQUAL "x86_64")
    set(VCPKG_TARGET_TRIPLET "x64-android" CACHE STRING "" FORCE)
  elseif(_holder_android_abi STREQUAL "armeabi-v7a")
    set(VCPKG_TARGET_TRIPLET "arm-neon-android" CACHE STRING "" FORCE)
  elseif(_holder_android_abi STREQUAL "x86")
    set(VCPKG_TARGET_TRIPLET "x86-android" CACHE STRING "" FORCE)
  else()
    message(FATAL_ERROR "Unsupported Android ABI: ${_holder_android_abi}")
  endif()
endif()

project(holder_android_native VERSION ${HOLDER_CORE_CMAKE_VERSION} LANGUAGES CXX)

set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory("${HOLDER_CORE_DIR}" "${CMAKE_BINARY_DIR}/holder-core")

add_library(holder_jni SHARED holder_jni.cpp holder_git_signer.cpp holder_keyring_bridge.cpp holder_storage_provider_bridge.cpp)
target_link_libraries(holder_jni PRIVATE Holder::Core)
