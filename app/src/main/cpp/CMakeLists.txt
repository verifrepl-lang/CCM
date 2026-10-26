cmake_minimum_required(VERSION 3.22.1)
project(crabpty C)

add_library(crabpty SHARED pty_jni.c)
target_link_libraries(crabpty log)
