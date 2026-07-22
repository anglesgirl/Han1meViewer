#ifndef KAFFU_H
#define KAFFU_H

#include <stddef.h>
#include <stdint.h>

// 增加这一段判断
#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uint8_t data[64];
    uint32_t datalen;
    unsigned long long bitlen;
    uint32_t state[8];
} KAFFU_SHA256_CTX;

void kaffu_sha256_init(KAFFU_SHA256_CTX *ctx);
void kaffu_sha256_update(KAFFU_SHA256_CTX *ctx, const uint8_t data[], size_t len);
void kaffu_sha256_final(KAFFU_SHA256_CTX *ctx, uint8_t hash[]);

#ifdef __cplusplus
}
#endif

#endif // KAFFU_H
