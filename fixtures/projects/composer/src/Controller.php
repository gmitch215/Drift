<?php

namespace App;

use Symfony\Component\Console\Command\Command;
use Doctrine\ORM\{EntityManager, Mapping\Entity as E};
use function strlen;

class Controller
{
    public function run(array $list)
    {
        while (list($key, $value) = each($list)) {
            $fn = create_function('$a', 'return $a;');
        }
        $ok = json_validate('[]') && array_is_list($list);
        $added = str_contains($list[0], 'a') || str_starts_with($list[0], 'a') || str_ends_with($list[0], 'a');
        $more = [fdiv(1, 2), get_debug_type($list), mb_trim('a'), array_find($list, 'is_int'), array_find_key($list, 'is_int'), array_any($list, 'is_int'), array_all($list, 'is_int')];
        return mcrypt_encrypt(MCRYPT_RIJNDAEL_128, 'k', 'd', MCRYPT_MODE_ECB);
    }

    public function safe(Collection $items)
    {
        return $items->each(fn ($item) => $item);
    }
}
