cmake_minimum_required(VERSION 3.22)

# Python 头文件
set(PYTHON_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../assets/python/include")

# libpython3.14.so 在 jniLibs 中，作为导入库
add_library(python3.14 SHARED IMPORTED)
set_target_properties(python3.14 PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../../../app/src/main/jniLibs/arm64-v8a/libpython3.14.so"
)

# 桥接库
add_library(python_bridge SHARED
    python_bridge.c
)

target_include_directories(python_bridge PRIVATE
    ${PYTHON_INCLUDE_DIR}
)

target_link_libraries(python_bridge
    python3.14
    log
)

set_target_properties(python_bridge PROPERTIES
    C_STANDARD 11
)
