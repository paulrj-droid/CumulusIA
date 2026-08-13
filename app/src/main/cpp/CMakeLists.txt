cmake_minimum_required(VERSION 3.22.1)
project("cumulus-native")

add_library(
        cumulus-native
        SHARED
        native-bridge.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        cumulus-native
        ${log-lib}
)
