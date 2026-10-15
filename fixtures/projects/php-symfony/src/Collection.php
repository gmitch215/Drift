<?php

namespace App;

class Collection implements \IteratorAggregate, \Countable
{
    private $items = [];

    public function getIterator()
    {
        return new \ArrayIterator($this->items);
    }

    #[\ReturnTypeWillChange]
    public function count()
    {
        return count($this->items);
    }
}
