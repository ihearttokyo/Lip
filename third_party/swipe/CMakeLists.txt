cmake_minimum_required(VERSION 3.22)
project(lip_swipe_core LANGUAGES CXX)
# Portable, Qt-free subset only. App linkage remains separately explicit.
add_library(lip_swipe_core STATIC src/swipe_net.cpp src/swipe_layout.cpp src/swipe_beam.cpp)
target_include_directories(lip_swipe_core PUBLIC include)
target_compile_features(lip_swipe_core PUBLIC cxx_std_17)
set_target_properties(lip_swipe_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
