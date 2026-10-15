<?php

namespace App;

use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Session\SessionInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;

class PageController extends AbstractController
{
    public function show(Request $request, SessionInterface $session)
    {
        $id = $request->get('id');
        $limit = $request->query->getInt('limit');
        $legacy = $this->container->get('session');
        $form = $this->createForm(PageType::class);

        return $this->renderForm('page.html.twig', ['form' => $form]);
    }

    public function owner($token, $user)
    {
        $roles = $token->getRoles();
        $name = $user->getUsername();
    }
}
