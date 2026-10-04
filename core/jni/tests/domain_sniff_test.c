#include <stdio.h>
#include <string.h>

#include "hev-domain-sniff.h"

static int test_http(void)
{
    static const unsigned char req[] =
        "GET /dns-query HTTP/1.1\r\nHost: Example.COM:443\r\nConnection: close\r\n\r\n";
    char domain[256] = { 0 };
    int res = hev_domain_sniff(req, sizeof(req) - 1, domain, sizeof(domain));
    return res == 1 && strcmp(domain, "example.com") == 0 ? 0 : 1;
}

static int test_tls_sni(void)
{
    static const unsigned char hello[] = {
        0x16,0x03,0x01,0x00,0x43,
        0x01,0x00,0x00,0x3f,
        0x03,0x03,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0x00,
        0x00,0x02,0x13,0x01,
        0x01,0x00,
        0x00,0x14,
        0x00,0x00,0x00,0x10,0x00,0x0e,0x00,0x00,0x0b,
        'e','x','a','m','p','l','e','.','c','o','m'
    };
    char domain[256] = { 0 };
    int res = hev_domain_sniff(hello, sizeof(hello), domain, sizeof(domain));
    return res == 1 && strcmp(domain, "example.com") == 0 ? 0 : 1;
}

static int test_ech_does_not_rewrite_outer_sni(void)
{
    static const unsigned char hello[] = {
        0x16,0x03,0x01,0x00,0x47,
        0x01,0x00,0x00,0x43,
        0x03,0x03,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0x00,
        0x00,0x02,0x13,0x01,
        0x01,0x00,
        0x00,0x18,
        0x00,0x00,0x00,0x10,0x00,0x0e,0x00,0x00,0x0b,
        'e','x','a','m','p','l','e','.','c','o','m',
        0xfe,0x0d,0x00,0x00
    };
    char domain[256] = { 0 };
    int res = hev_domain_sniff(hello, sizeof(hello), domain, sizeof(domain));
    return res == 0 ? 0 : 1;
}

int main(void)
{
    if (test_http()) {
        fprintf(stderr, "HTTP Host sniff failed\n");
        return 1;
    }
    if (test_tls_sni()) {
        fprintf(stderr, "TLS SNI sniff failed\n");
        return 1;
    }
    if (test_ech_does_not_rewrite_outer_sni()) {
        fprintf(stderr, "ECH safety check failed\n");
        return 1;
    }
    puts("domain sniff tests passed");
    return 0;
}
