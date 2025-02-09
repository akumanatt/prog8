%import syslib
%import textio
%import test_stack


spritedata $2000 {
    ; this memory block contains the sprite data
    ; it must start on an address aligned to 64 bytes.
    %option force_output    ; make sure the data in this block appears in the resulting program

    ubyte[] sprites = [
                                %00000000,%00000000,%00000000,
                                %00000000,%00111100,%00000000,
                                %00000000,%11111111,%00000000,
                                %00000001,%11111101,%10000000,
                                %00000001,%11111111,%10000000,
                                %00000011,%11111111,%11000000,
                                %00000011,%11111111,%11000000,
                                %00000011,%11111111,%11000000,
                                %00000001,%11111111,%10000000,
                                %00000001,%11111111,%10000000,
                                %00000000,%11111111,%00000000,
                                %00000000,%00111100,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,

                                0,

                                %00000000,%00111110,%00000000,
                                %00000000,%11111111,%10000000,
                                %00000001,%11111110,%11000000,
                                %00000011,%11111111,%01100000,
                                %00000011,%11111111,%11100000,
                                %00000111,%11111111,%11110000,
                                %00000111,%11111111,%11110000,
                                %00000111,%11111111,%11110000,
                                %00000111,%11111111,%11110000,
                                %00000011,%11111111,%11100000,
                                %00000011,%11111111,%11100000,
                                %00000001,%11111111,%11000000,
                                %00000000,%11111111,%10000000,
                                %00000000,%00111110,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,
                                %00000000,%00000000,%00000000,

                                0
                           ]
}


main {

    const uword width = 255
    const uword height = 200

    ; vertices
    word[] xcoor = [ -100, -100, -100, -100,  100,  100,  100, 100 ]
    word[] ycoor = [ -100, -100,  100,  100, -100, -100,  100, 100 ]
    word[] zcoor = [ -100,  100, -100,  100, -100,  100, -100, 100 ]

    ; storage for rotated coordinates
    word[len(xcoor)] rotatedx
    word[len(ycoor)] rotatedy
    word[len(zcoor)] rotatedz


    sub start()  {

        c64.SPENA = 255                 ; enable all sprites

        uword anglex
        uword angley
        uword anglez
        repeat {
            c64.TIME_LO=0
            rotate_vertices(msb(anglex), msb(angley), msb(anglez))
            position_sprites()
            anglex-=500
            angley+=217
            anglez+=452
            txt.plot(0,0)
            txt.print("3d cube! (sprites) ")
            txt.print_ub(c64.TIME_LO)
            txt.print(" jiffies/fr = ")
            txt.print_ub(60/c64.TIME_LO)
            txt.print(" fps")

            ; test_stack.test()
        }
    }

    sub rotate_vertices(ubyte ax, ubyte ay, ubyte az) {
        ; rotate around origin (0,0,0)

        ; set up the 3d rotation matrix values
        word wcosa = cos8(ax)
        word wsina = sin8(ax)
        word wcosb = cos8(ay)
        word wsinb = sin8(ay)
        word wcosc = cos8(az)
        word wsinc = sin8(az)

        word wcosa_sinb = wcosa*wsinb / 128
        word wsina_sinb = wsina*wsinb / 128

        word Axx = wcosa*wcosb / 128
        word Axy = (wcosa_sinb*wsinc - wsina*wcosc) / 128
        word Axz = (wcosa_sinb*wcosc + wsina*wsinc) / 128
        word Ayx = wsina*wcosb / 128
        word Ayy = (wsina_sinb*wsinc + wcosa*wcosc) / 128
        word Ayz = (wsina_sinb*wcosc - wcosa*wsinc) / 128
        word Azx = -wsinb
        word Azy = wcosb*wsinc / 128
        word Azz = wcosb*wcosc / 128

        ubyte @zp i
        for i in 0 to len(xcoor)-1 {
            ; don't normalize by dividing by 128, instead keep some precision for perspective calc later
            rotatedx[i] = (Axx*xcoor[i] + Axy*ycoor[i] + Axz*zcoor[i])
            rotatedy[i] = (Ayx*xcoor[i] + Ayy*ycoor[i] + Ayz*zcoor[i])
            rotatedz[i] = (Azx*xcoor[i] + Azy*ycoor[i] + Azz*zcoor[i])
        }
    }


    sub position_sprites() {

        ; set each of the 8 sprites to the correct vertex of the cube

        ; first sort vertices to sprite order so the back/front order is correct as well
        ; (simple bubble sort as it's only 8 items to sort)
        ubyte @zp i
        ubyte @zp i1
        for i in 6 downto 0 {
            for i1 in 0 to i {
                ubyte i2 = i1+1
                if(rotatedz[i1] > rotatedz[i2]) {
                    swap(rotatedx[i1], rotatedx[i2])
                    swap(rotatedy[i1], rotatedy[i2])
                    swap(rotatedz[i1], rotatedz[i2])
                }
            }
        }

        ubyte[] spritecolors = [1,1,7,15,12,11,9,9]

        for i in 0 to 7 {
            word @zp zc = rotatedz[i]
            word persp = 300+zc/256
            ubyte sx = rotatedx[i] / persp + width/2 as ubyte + 20
            ubyte sy = rotatedy[i] / persp + height/2 as ubyte + 40

            c64.SPXYW[i] = mkword(sy, sx)

            if(zc < 30*128)
                c64.SPRPTR[i] = $2000/64 +1     ; large ball
            else
                c64.SPRPTR[i] = $2000/64        ; small ball

            c64.SPCOL[i] = spritecolors[(zc>>13) as ubyte + 4]      ; further away=darker color
        }
    }
}
