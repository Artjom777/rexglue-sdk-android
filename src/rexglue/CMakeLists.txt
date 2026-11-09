# ReXGlue 

add_executable(rexglue
    main.cpp
    ui/ui.cpp
    ui/progress.cpp
    commands/codegen_command.cpp
    commands/init_command.cpp
    commands/test_recompiler.cpp
    commands/project_scan.cpp
)

rex_config_postfix(rexglue)
rexglue_add_version_resource(rexglue DESCRIPTION "ReXGlue recompiler")

target_include_directories(rexglue
    PRIVATE
        ${REXGLUE_ROOT}/src
        ${REXGLUE_ROOT}/thirdparty/disasm
        ${REXGLUE_ROOT}/thirdparty/cli11/include
)

target_link_libraries(rexglue
    PRIVATE
        rexcodegen
)

if(ANDROID)
    target_link_libraries(rexglue PRIVATE android log)

    add_library(rexglue_jni SHARED
        rexglue_jni.cpp
        ui/ui.cpp
        ui/progress.cpp
        commands/codegen_command.cpp
        commands/init_command.cpp
        commands/test_recompiler.cpp
        commands/project_scan.cpp
    )
    target_include_directories(rexglue_jni PRIVATE
        ${REXGLUE_ROOT}/src
        ${REXGLUE_ROOT}/thirdparty/disasm
        ${REXGLUE_ROOT}/thirdparty/cli11/include
    )
    target_link_libraries(rexglue_jni PRIVATE
        rexcodegen
        android
        log
    )
    set_target_properties(rexglue_jni PROPERTIES OUTPUT_NAME "rexglue_jni")
    rex_target_pch(rexglue_jni <CLI/CLI.hpp>)
    install(TARGETS rexglue_jni
        EXPORT rexglueTargets
        LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
        RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
    )
endif()

rex_target_pch(rexglue <CLI/CLI.hpp>)

# Delay-load FidelityFX DLLs so rexglue can run in tool mode without them.
if(WIN32)
    if(TARGET amd_fidelityfx_vk)
        target_link_options(rexglue PRIVATE
            "LINKER:/DELAYLOAD:$<TARGET_FILE_NAME:amd_fidelityfx_vk>")
    endif()
    if(TARGET amd_fidelityfx_dx12)
        target_link_options(rexglue PRIVATE
            "LINKER:/DELAYLOAD:$<TARGET_FILE_NAME:amd_fidelityfx_dx12>")
    endif()
    if(TARGET amd_fidelityfx_vk OR TARGET amd_fidelityfx_dx12)
        target_link_libraries(rexglue PRIVATE delayimp)
    endif()
endif()

add_executable(rex::rexglue ALIAS rexglue)
