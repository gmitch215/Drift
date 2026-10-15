<?php

namespace Drupal\Tests\legacy;

use Drupal\simpletest\WebTestBase;

class LegacyTest extends WebTestBase {

  public function testPage() {
    $this->drupalGet('<front>');
  }

}
