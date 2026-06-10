get_filename_component(CMAKE_BIN_DIR "${CMAKE_COMMAND}" DIRECTORY)
set(CMAKE_MAKE_PROGRAM "${CMAKE_BIN_DIR}/ninja" CACHE FILEPATH "Ninja executable" FORCE)
set(CMAKE_C_COMPILER "/usr/bin/clang" CACHE FILEPATH "Host C compiler" FORCE)
set(CMAKE_CXX_COMPILER "/usr/bin/clang++" CACHE FILEPATH "Host C++ compiler" FORCE)
