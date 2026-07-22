cmake_minimum_required(VERSION 3.22.1)
project("Han1meViewer")

add_library(
    chino
    SHARED
    chino.cpp
    kaffu.c
)

find_library(log-lib log)
target_link_libraries(chino ${log-lib})