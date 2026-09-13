cmake_minimum_required(VERSION 3.22.1)

project("dyparse")

# 命名为一个常见的名字，比如 libnet_utils.so
add_library(net_utils SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(net_utils ${log-lib})

# 加固：隐藏符号 + 优化 + 栈保护，提高逆向成本
target_compile_options(net_utils PRIVATE
    -fvisibility=hidden
    -O2
    -fstack-protector-strong
)

set_target_properties(net_utils PROPERTIES
    VISIBILITY_INLINES_HIDDEN YES
)
