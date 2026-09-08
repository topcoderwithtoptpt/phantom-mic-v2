//
// Created by amin on 7/24/24.
//

#ifndef PHANTOMMIC_HOOK_COMPAT_H
#define PHANTOMMIC_HOOK_COMPAT_H

#include <string>
#include <vector>
#include "utils.h"
#include "KittyScanner.hpp"

namespace HookCompat {
    inline std::string get_library_name() {
        if (get_sdk_int() <= 24) {
            return "libmedia.so";
        } else {
            return "libaudioclient.so";
        }
    }

    inline uintptr_t get_symbol(ElfScanner& elfScanner, const std::vector<std::string>& possible_symnames) {
        for (const std::string& symname : possible_symnames) {
            uintptr_t sym = elfScanner.findSymbol(symname);
            if (sym != 0) {
                return sym;
            }
        }
        return 0;
    }

    inline uintptr_t get_stop_symbol(ElfScanner& elfScanner) {
        return get_symbol(elfScanner, {
            "_ZN7android11AudioRecord4stopEv"
        });
    }

    inline uintptr_t get_obtainBuffer_symbol(ElfScanner& elfScanner) {
        return get_symbol(elfScanner, {
            "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEPK8timespecPS3_Pj",
            "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEPK8timespecPS3_Pm",
            "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEPK8timespecPS3_PjS6_",
            "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEPK8timespecPS3_PmS6_"
        });
    }

    inline uintptr_t get_read_symbol(ElfScanner& elfScanner) {
        return get_symbol(elfScanner, {
            "_ZN7android11AudioRecord4readEPvmb",
            "_ZN7android11AudioRecord4readEPvjb",
            "_ZN7android11AudioRecord4readEPvm",
            "_ZN7android11AudioRecord4readEPvj"
        });
    }

    inline uintptr_t get_set_symbol(ElfScanner& elfScanner) {
        return get_symbol(elfScanner, {
            // Android 7,8
            "_ZN7android11AudioRecord3setE14audio_source_tj14audio_format_tjmPFviPvS3_ES3_jb15audio_session_tNS0_13transfer_typeE19audio_input_flags_tjiPK18audio_attributes_t",
            "_ZN7android11AudioRecord3setE14audio_source_tj14audio_format_tjjPFviPvS3_ES3_jb15audio_session_tNS0_13transfer_typeE19audio_input_flags_tjiPK18audio_attributes_t",
            // Android 9
            "_ZN7android11AudioRecord3setE14audio_source_tj14audio_format_tjmPFviPvS3_ES3_jb15audio_session_tNS0_13transfer_typeE19audio_input_flags_tjiPK18audio_attributes_ti",
            "_ZN7android11AudioRecord3setE14audio_source_tj14audio_format_tjjPFviPvS3_ES3_jb15audio_session_tNS0_13transfer_typeE19audio_input_flags_tjiPK18audio_attributes_ti",
            // Android 10,11
            "_ZN7android11AudioRecord3setE14audio_source_tj14audio_format_tjmPFviPvS3_ES3_jb15audio_session_tNS0_13transfer_typeE19audio_input_flags_tjiPK18audio_attributes_ti28audio_microphone_direction_tf",
            "_ZN7android11AudioRecord3setE14audio_source_tj14audio_format_tjjPFviPvS3_ES3_jb15audio_session_tNS0_13transfer_typeE19audio_input_flags_tjiPK18audio_attributes_ti28audio_microphone_direction_tf",
            // Android 12
            "_ZN7android11AudioRecord3setE14audio_source_tj14audio_format_t20audio_channel_mask_tmPFviPvS4_ES4_jb15audio_session_tNS0_13transfer_typeE19audio_input_flags_tjiPK18audio_attributes_ti28audio_microphone_direction_tfi",
            // Android 13, 14, 15
            "_ZN7android11AudioRecord3setE14audio_source_tj14audio_format_t20audio_channel_mask_tmRKNS_2wpINS0_20IAudioRecordCallbackEEEjb15audio_session_tNS0_13transfer_typeE19audio_input_flags_tjiPK18audio_attributes_ti28audio_microphone_direction_tfi"
        });
    }
}

#endif //PHANTOMMIC_HOOK_COMPAT_H
