cmake_minimum_required(VERSION 3.22)
project(hotreload_agent)

add_library(hotreload_agent SHARED hotreload_agent.cpp)
target_include_directories(hotreload_agent PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

find_library(log-lib log)
target_link_libraries(hotreload_agent ${log-lib})
