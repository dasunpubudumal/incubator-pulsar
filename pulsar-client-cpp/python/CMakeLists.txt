#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

INCLUDE_DIRECTORIES("${Boost_INCLUDE_DIRS}" "${PYTHON_INCLUDE_DIRS}")

ADD_LIBRARY(_pulsar SHARED src/pulsar.cc src/producer.cc src/consumer.cc
                            src/config.cc src/enums.cc src/client.cc
                            src/message.cc src/authentication.cc
                            src/reader.cc)
SET(CMAKE_SHARED_LIBRARY_PREFIX )
SET(CMAKE_SHARED_LIBRARY_SUFFIX .so)


if (CMAKE_CXX_FLAGS_PYTHON)
    SET(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS_PYTHON}")
endif()

if("${CMAKE_CXX_COMPILER_ID}" STREQUAL "Clang")
  set(CMAKE_SHARED_LIBRARY_CREATE_CXX_FLAGS "${CMAKE_SHARED_LIBRARY_CREATE_CXX_FLAGS} -undefined dynamic_lookup")
endif()

# Try all possible boost-python variable namings
set(PYTHON_WRAPPER_LIBS ${Boost_PYTHON_LIBRARY} ${Boost_PYTHON3_LIBRARY}
                        ${Boost_PYTHON27-MT_LIBRARY} ${Boost_PYTHON37-MT_LIBRARY})

if (APPLE)
    target_link_libraries(_pulsar -Wl,-all_load pulsarStatic ${PYTHON_WRAPPER_LIBS})
else ()
    set (CMAKE_SHARED_LINKER_FLAGS " -static-libgcc  -static-libstdc++")
    target_link_libraries(_pulsar pulsarStatic ${PYTHON_WRAPPER_LIBS})
endif ()
