<?php
$ok = str_contains("order-2041 paid", "paid");
echo $ok ? "PASS\n" : "FAIL\n";
exit($ok ? 0 : 1);
