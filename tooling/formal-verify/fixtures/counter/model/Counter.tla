------------------------------ MODULE Counter ------------------------------
(* A counter that stops at Limit. The fixture for tooling/formal-verify/test.sh. *)
EXTENDS Naturals

CONSTANTS Limit

VARIABLES n

Init == n = 0
Next == n < Limit /\ n' = n + 1
Spec == Init /\ [][Next]_n

AtMostLimit == n <= Limit
BelowThree == n < 3

=============================================================================
