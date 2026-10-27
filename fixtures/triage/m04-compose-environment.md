My container ignores the umask. Compose file:

```
services:
  app:
    image: acme/app:3.2
    environment:
      - PUID=1000
      - UMASK=002
      - API_TOKEN=abc123
```
