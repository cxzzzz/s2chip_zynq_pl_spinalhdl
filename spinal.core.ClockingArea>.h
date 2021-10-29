#define OUTCMDREGS = 0x0
#define OUTCMDREGS = 0x8
#define OUTCMDREGS = 0x10
#define OUTCMDREGS = 0x18
#define INCMDREGS  = 0x20
#define INCMDREGS  = 0x28
#define INCMDREGS  = 0x30
#define INCMDREGS  = 0x38


typedef union{
    uint64_t val;
    struct{
        uint64_t connectRegInst:32;
        uint64_t na_:32;
    } reg;
} outcmdregs_t

typedef union{
    uint64_t val;
    struct{
        uint64_t connectRegInst:32;
        uint64_t na_:32;
    } reg;
} outcmdregs_t

typedef union{
    uint64_t val;
    struct{
        uint64_t connectRegInst:32;
        uint64_t na_:32;
    } reg;
} outcmdregs_t

typedef union{
    uint64_t val;
    struct{
        uint64_t na_:64;
    } reg;
} outcmdregs_t

typedef union{
    uint64_t val;
    struct{
        uint64_t connectRegInst:32;
        uint64_t na_:32;
    } reg;
} incmdregs_t

typedef union{
    uint64_t val;
    struct{
        uint64_t connectRegInst:32;
        uint64_t na_:32;
    } reg;
} incmdregs_t

typedef union{
    uint64_t val;
    struct{
        uint64_t connectRegInst:32;
        uint64_t na_:32;
    } reg;
} incmdregs_t

typedef union{
    uint64_t val;
    struct{
        uint64_t na_:64;
    } reg;
} incmdregs_t