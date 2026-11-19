FROM gcc:16.2.0
WORKDIR /src
RUN curl -sL https://mirrors.develooper.com/perl/historical-perl/perl3.0.tar.gz | tar xz
WORKDIR /src/perl3.0
RUN make CC="gcc -std=c90 -traditional-cpp -fcommon -fpermissive -O0 -ffreestanding \
    -fpack-struct=4 -fno-strict-aliasing -fno-short-enums \
    -fno-align-functions -fno-align-labels -fno-align-loops -fno-align-jumps \
    -fno-jump-tables -fno-thread-jumps -fno-crossjumping -fno-gcse \
    -Wno-builtin-declaration-mismatch -DMEMCPY -Dstrchr=index -Dstrrchr=rindex \
    -UBYTEORDER -DBYTEORDER=01234567 -UCHARSPRINTF -UCPPSTDIN -DCPPSTDIN=\"gcc -E\""
