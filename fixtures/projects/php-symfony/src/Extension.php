<?php

namespace App;

class Extension extends \Twig_Extension
{
    public function getFilters()
    {
        return [new \Twig_SimpleFilter('shout', 'strtoupper')];
    }
}
