//
// Created by maks on 23.01.2025.
//

#ifndef POJAVLAUNCHER_NATIVE_HOOKS_H
#define POJAVLAUNCHER_NATIVE_HOOKS_H

#include <bytehook.h>

typedef bytehook_stub_t (*bytehook_hook_all_t)(const char *callee_path_name, const char *sym_name, void *new_func,
                                               bytehook_hooked_t hooked, void *hooked_arg);
typedef bytehook_stub_t (*bytehook_hook_partial_t)(bytehook_caller_allow_filter_t caller_allow_filter,
                                                   void *caller_allow_filter_arg,
                                                   const char *callee_path_name,
                                                   const char *sym_name,
                                                   void *new_func,
                                                   bytehook_hooked_t hooked,
                                                   void *hooked_arg);

void create_chmod_hooks(bytehook_hook_all_t bytehook_hook_all_p);
void create_sdl_hooks(bytehook_hook_all_t bytehook_hook_all_p);
void create_dlopen_hooks(bytehook_hook_all_t bytehook_hook_all_p,
                         bytehook_hook_partial_t bytehook_hook_partial_p);

#endif //POJAVLAUNCHER_NATIVE_HOOKS_H
