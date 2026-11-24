cmake_minimum_required(VERSION 3.22)
project(autobridge CXX C)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
set(AASDK ${ROOT}/third_party/aasdk)
set(DEPS ${ROOT}/native/deps)
if(NOT EXISTS ${DEPS}/${ANDROID_ABI}/lib/libprotobuf.a)
    message(FATAL_ERROR "Native deps missing: run native/build-deps.sh first")
endif()

# protobuf + abseil come with CMake config files from build-deps.sh
list(APPEND CMAKE_FIND_ROOT_PATH ${DEPS}/${ANDROID_ABI})
find_package(protobuf CONFIG REQUIRED)

# Generate C++ for every Android Auto .proto that aasdk ships.
set(PROTO_ROOT ${AASDK}/protobuf)
set(GEN ${CMAKE_CURRENT_BINARY_DIR}/gen)
file(GLOB_RECURSE PROTOS ${PROTO_ROOT}/aap_protobuf/*.proto)
set(PROTO_SRCS)
foreach(p ${PROTOS})
    file(RELATIVE_PATH rel ${PROTO_ROOT} ${p})
    string(REGEX REPLACE "\\.proto$" "" base ${rel})
    list(APPEND PROTO_SRCS ${GEN}/${base}.pb.cc)
    add_custom_command(OUTPUT ${GEN}/${base}.pb.cc ${GEN}/${base}.pb.h
        COMMAND ${CMAKE_COMMAND} -E make_directory ${GEN}
        COMMAND ${DEPS}/protoc -I${PROTO_ROOT} --cpp_out=${GEN} ${p}
        DEPENDS ${p})
endforeach()

# aasdk: everything except USB (we are wireless-only) and its unit tests.
file(GLOB_RECURSE AASDK_SRCS ${AASDK}/src/*.cpp)
list(FILTER AASDK_SRCS EXCLUDE REGEX "(/USB/|USBTransport|\\.ut\\.cpp$)")

add_library(aasdk STATIC ${AASDK_SRCS} ${PROTO_SRCS})
target_include_directories(aasdk PUBLIC ${AASDK}/include ${GEN} ${DEPS}/boost ${DEPS}/${ANDROID_ABI}/include)
target_compile_definitions(aasdk PUBLIC BOOST_ASIO_DISABLE_STD_ALIGNED_ALLOC)
target_link_libraries(aasdk PUBLIC protobuf::libprotobuf
    ${DEPS}/${ANDROID_ABI}/lib/libssl.a ${DEPS}/${ANDROID_ABI}/lib/libcrypto.a log)

add_library(autobridge SHARED bridge.cpp)
target_link_libraries(autobridge aasdk android log)
