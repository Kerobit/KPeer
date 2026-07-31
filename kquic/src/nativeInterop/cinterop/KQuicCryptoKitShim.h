#ifndef KQUIC_CRYPTOKIT_SHIM_H
#define KQUIC_CRYPTOKIT_SHIM_H

#include <stdint.h>

int32_t kquic_aes_gcm_seal(
    const uint8_t *key,
    int32_t key_length,
    const uint8_t *nonce,
    int32_t nonce_length,
    const uint8_t *aad,
    int32_t aad_length,
    const uint8_t *plaintext,
    int32_t plaintext_length,
    uint8_t *output,
    int32_t output_capacity
);

int32_t kquic_aes_gcm_open(
    const uint8_t *key,
    int32_t key_length,
    const uint8_t *nonce,
    int32_t nonce_length,
    const uint8_t *aad,
    int32_t aad_length,
    const uint8_t *ciphertext,
    int32_t ciphertext_length,
    uint8_t *output,
    int32_t output_capacity
);

#endif
