package web.presentation.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ui.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/")
public class HomeController {

  Log log = LogFactory.getLog(HomeController.class);

  @GetMapping
  String index(Model model) {

    log.info("home-controller-index");

    model.addAttribute("message", "HelloWorld!");

    return "index";
  }

  @GetMapping("/hoge")
  String hoge(Model model) {

    log.info("home-controller-hoge");

    model.addAttribute("message", "Hoge!");

    return "index";
  }
}
