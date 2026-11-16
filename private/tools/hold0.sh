# held Down on the Xiaomi remote: 1 = down, 2 = repeat, 0 = up
E=/dev/input/event0
sendevent $E 1 108 1; sendevent $E 0 0 0
i=0; while [ $i -lt 35 ]; do sendevent $E 1 108 2; sendevent $E 0 0 0; i=$((i+1)); done
sendevent $E 1 108 0; sendevent $E 0 0 0
