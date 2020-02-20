package com.vb.fitnessapp.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.vb.fitnessapp.domain.Food;

import com.vb.fitnessapp.dto.FoodDTO;
import com.vb.fitnessapp.dto.FoodEatenDTO;
import com.vb.fitnessapp.dto.UserDTO;
import com.vb.fitnessapp.service.FoodService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
final class FoodController extends AbstractController {

    private final FoodService foodService;

    @Autowired
    public FoodController(final FoodService foodService) {
        this.foodService = foodService;
    }

    @GetMapping("/foodeaten/{date}")
    public final List<FoodEatenDTO> loadFoodsEaten(
            @PathVariable(name = "date") final String dateString,
            final HttpServletRequest request
    ) {
        final UserDTO userDTO = currentAuthenticatedUser(request);
        final Date date = dateString == null ? todaySqlDateForUser(userDTO) : stringToSqlDate(dateString);
        return foodService.findEatenOnDate(userDTO.getId(), date);
    }

    @PostMapping("/foodeaten")
    public final FoodEatenDTO addFoodEaten(
            @RequestBody final Map<String, Object> payload,
            final HttpServletRequest request
    ) {
        final UserDTO userDTO = currentAuthenticatedUser(request);
        final String foodIdString = (String) payload.get("id");
        final String dateString = (String) payload.get("date");
        final Date date = dateString == null ? todaySqlDateForUser(userDTO) : stringToSqlDate(dateString);
        final UUID foodId = UUID.fromString(foodIdString);
        return foodService.addFoodEaten(userDTO.getId(), foodId, date);
    }

    @PutMapping("/foodeaten/{id}")
    public final FoodEatenDTO updateFoodEaten(
            @PathVariable(name = "id") final String foodEatenIdString,
            @RequestBody final Map<String, Object> payload,
            final HttpServletRequest request,
            final HttpServletResponse response
    ) {
        final UUID foodEatenId = UUID.fromString(foodEatenIdString);
        final FoodEatenDTO foodEatenDTO = foodService.findFoodEatenById(foodEatenId);
        if (foodEatenDTO == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        final UserDTO userDTO = currentAuthenticatedUser(request);
        if (!foodEatenDTO.getUserId().equals(userDTO.getId())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        Food.ServingType servingType;
        Double servingQty;
        try {
            servingType = Food.ServingType.fromString((String) payload.get("servingType"));
            servingQty = Double.parseDouble((String) payload.get("servingQty"));
            foodService.updateFoodEaten(foodEatenId, servingQty, servingType);
        } catch (final NullPointerException | NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }

        return foodService.updateFoodEaten(foodEatenId, servingQty, servingType);
    }

    @DeleteMapping("/foodeaten/{id}")
    public final void deleteFoodEaten(
            @PathVariable(name = "id") final String idString,
            final HttpServletRequest request,
            final HttpServletResponse response
    ) {
        final UUID foodEatenId = UUID.fromString(idString);
        final FoodEatenDTO foodEatenDTO = foodService.findFoodEatenById(foodEatenId);
        if (foodEatenDTO == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        final UserDTO userDTO = currentAuthenticatedUser(request);
        if (!foodEatenDTO.getUserId().equals(userDTO.getId())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        foodService.deleteFoodEaten(foodEatenId);
    }

    @GetMapping("/food/recent/{date}")
    public final List<FoodDTO> loadRecentFoods(
            @PathVariable(name = "date") final String dateString,
            final HttpServletRequest request
    ) {
        final UserDTO userDTO = currentAuthenticatedUser(request);
        final Date date = dateString == null ? todaySqlDateForUser(userDTO) : stringToSqlDate(dateString);
        return foodService.findEatenRecently(userDTO.getId(), date);
    }










    @RequestMapping(value = "/food/search/{searchString}")
    @ResponseBody
    public final List<FoodDTO> searchFoods(
            @PathVariable final String searchString,
            final HttpServletRequest request
    ) {
        final UserDTO userDTO = currentAuthenticatedUser(request);
        return foodService.searchFoods(userDTO.getId(), searchString);
    }

    @RequestMapping(value = "/food/get/{foodId}")
    @ResponseBody
    public final FoodDTO getFood(
            @PathVariable final String foodId,
            final HttpServletRequest request
    ) {
        final UserDTO userDTO = currentAuthenticatedUser(request);
        FoodDTO foodDTO = foodService.getFoodById(UUID.fromString(foodId));
        // Only return foods that are visible to the requesting user
        if (foodDTO.getOwnerId() != null && !foodDTO.getOwnerId().equals(userDTO.getId())) {
            foodDTO = null;
        }
        return foodDTO;
    }

    @RequestMapping(value = "/food/update")
    @ResponseBody
    public final String createOrUpdateFood(
            @ModelAttribute final FoodDTO foodDTO,
            final HttpServletRequest request,
            final Model model
    ) {
        final UserDTO userDTO = currentAuthenticatedUser(request);
        String resultMessage;
        if (foodDTO.getId() == null) {
            resultMessage = foodService.createFood(foodDTO, userDTO);
        } else {
            resultMessage = foodService.updateFood(foodDTO, userDTO);
        }
        return resultMessage;
    }

}
