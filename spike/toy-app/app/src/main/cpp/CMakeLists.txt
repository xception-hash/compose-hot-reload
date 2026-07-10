cmake_minimum_required(VERSION 3.22)
project(hotreload_agent)

# T30 item 1: compile runtime-client's interpreter_jni.cpp VERBATIM from its real path (never
# copied into spike/, so it can't drift) so the interpreter's invokespecial*/enterMonitor/
# exitMonitor natives exist in the toy app's .so. Its registration export is JNI-mangled for
# dev.hotreload.client.HotSwap (absent in the toy app); interp_bridge.cpp forwards the toy-side
# name to it.
set(INTERP_JNI
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../runtime-client/lib/src/main/cpp/interpreter_jni.cpp)

add_library(hotreload_agent SHARED hotreload_agent.cpp interp_bridge.cpp ${INTERP_JNI})
target_include_directories(hotreload_agent PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

find_library(log-lib log)
target_link_libraries(hotreload_agent ${log-lib})
