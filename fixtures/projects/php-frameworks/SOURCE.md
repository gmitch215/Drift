# php-frameworks

`composer.lock` has one entry each for laravel/framework v13.0.0, typo3/cms-core v13.4.0 and
johnpbloch/wordpress-core 6.6.0. Name, version and `require.php` come from the Packagist p2 metadata
(`https://repo.packagist.org/p2/<vendor>/<package>.json`, read 2026-10-15); the rest of a real lock
entry is left out. The johnpbloch `require.php` is a mirror value (the same package lists `>=5.3.2`
for 6.9.8), so no rule cites it.
