%target cx16
%import cx16textio
;%import c64flt
;%option enable_floats
; %zeropage kernalsafe


main {


    sub start() {

        c64.CHROUT('*')

;asmsub  clear_screen (ubyte char @ A, ubyte color @ Y) clobbers(A)  { ...}
; TODO dont cause name conflict if we define sub or sub with param 'color' or even a var 'color' later.

;   sub color(...) {}
;   sub other(ubyte color) {}    ; TODO don't cause name conflict


    }

}
