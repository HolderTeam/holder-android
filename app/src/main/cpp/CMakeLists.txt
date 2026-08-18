cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(HOLDER_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../holder-core")
include("${HOLDER_CORE_DIR}/cmake/HolderVersion.cmake")

project(holder_android_native VERSION ${HOLDER_CORE_VERSION} LANGUAGES CXX)

add_library(holder_jni SHARED
  holder_jni.cpp
  "${HOLDER_CORE_DIR}/src/capi/version.cpp"
)

target_include_directories(holder_jni PRIVATE
  "${HOLDER_CORE_DIR}/include"
)

target_compile_definitions(holder_jni PRIVATE
  HOLDER_VERSION_STRING="${HOLDER_CORE_VERSION}"
  HOLDER_VERSION_MAJOR=${PROJECT_VERSION_MAJOR}
  HOLDER_VERSION_MINOR=${PROJECT_VERSION_MINOR}
  HOLDER_VERSION_PATCH=${PROJECT_VERSION_PATCH}
)
