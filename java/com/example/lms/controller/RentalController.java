package com.example.lms.controller;

import com.example.lms.domain.Rental;
import com.example.lms.repository.RentalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequestMapping("/rentals")
@RequiredArgsConstructor
public class RentalController {

    private final RentalRepository rentalRepository;

    // 1. 대여 목록 조회
    @GetMapping
    public String listRentals(Model model) {
        List<Rental> rentals = rentalRepository.findAll();
        model.addAttribute("rentals", rentals);
        return "rentals/rentals"; // templates/rentals/rentals.html
    }

    // 2. 대여 등록 폼
    @GetMapping("/new")
    public String showRentalForm(Model model) {
        model.addAttribute("rental", new Rental());
        return "rentals/rental-form"; // templates/rentals/rental-form.html
    }

    // 3. 대여 등록 처리
    @PostMapping
    public String createRental(@ModelAttribute Rental rental, RedirectAttributes redirectAttributes) {
        rentalRepository.save(rental);
        redirectAttributes.addFlashAttribute("message", "새로운 대여가 성공적으로 등록되었습니다.");
        return "redirect:/rentals";
    }

    // 4. 대여 상세 조회
    @GetMapping("/{id}")
    public String rentalDetail(@PathVariable Long id, Model model) {
        Rental rental = rentalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid rental Id:" + id));
        model.addAttribute("rental", rental);
        return "rentals/rental-detail"; // templates/rentals/rental-detail.html
    }

    // 5. 대여 수정 폼
    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id, Model model) {
        Rental rental = rentalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid rental Id:" + id));
        model.addAttribute("rental", rental);
        return "rentals/rental-form"; // 등록 폼을 수정 폼으로 재사용
    }

    // 6. 대여 수정 처리
    @PutMapping("/{id}")
    public String updateRental(@PathVariable Long id, @ModelAttribute Rental rentalDetails, RedirectAttributes redirectAttributes) {
        Rental rental = rentalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid rental Id:" + id));

        rental.setBookTitle(rentalDetails.getBookTitle());
        rental.setRenterName(rentalDetails.getRenterName());
        rentalRepository.save(rental);

        redirectAttributes.addFlashAttribute("message", "대여 정보가 성공적으로 수정되었습니다.");
        return "redirect:/rentals";
    }

    // 7. 반납 처리
    @PostMapping("/{id}/return")
    public String returnRental(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Rental rental = rentalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid rental Id:" + id));

        rental.setActive(false);
        rental.setReturnedAt(LocalDateTime.now());
        rentalRepository.save(rental);

        redirectAttributes.addFlashAttribute("message", "반납 처리가 완료되었습니다.");
        return "redirect:/rentals";
    }

    // 8. 삭제 처리
    @PostMapping("/{id}/delete")
    public String deleteRental(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        rentalRepository.deleteById(id);
        redirectAttributes.addFlashAttribute("message", "대여 기록이 삭제되었습니다.");
        return "redirect:/rentals";
    }
}
