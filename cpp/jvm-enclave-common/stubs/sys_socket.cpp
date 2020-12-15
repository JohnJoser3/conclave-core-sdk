//
// OS Stubs for functions declared in sys/socket.h
//
#include "vm_enclave_layer.h"

//////////////////////////////////////////////////////////////////////////////
// Stub functions to satisfy the linker
STUB(accept);
STUB(bind);
STUB(connect);
STUB(getsockname);
STUB(getsockopt);
STUB(listen);
STUB(recv);
STUB(recvfrom);
STUB(send);
STUB(sendto);
STUB(setsockopt);
STUB(shutdown);
STUB(socket);
STUB(socketpair);

extern "C" {

}
