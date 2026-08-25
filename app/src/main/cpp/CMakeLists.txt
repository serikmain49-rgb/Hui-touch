# HUI-TOUCH: нативный движок (NDK)
# Читает гироскоп через ASensorManager на максимальной частоте и через JNI
# передаёт готовые координаты в Java без участия Looper/Handler.
cmake_minimum_required(VERSION 3.22.1)

project("huitouch" LANGUAGES CXX)

add_library(huitouch SHARED native-lib.cpp)

target_compile_options(huitouch PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(huitouch
        ${log-lib}
        ${android-lib})
