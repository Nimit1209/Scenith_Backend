package com.example.Scenith.service.imageService;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.ImageTemplate;
import com.example.Scenith.repository.imagerepository.ImageTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ImageTemplateService {

    private static final Logger logger = LoggerFactory.getLogger(ImageTemplateService.class);

    private final ImageTemplateRepository templateRepository;

    public ImageTemplateService(ImageTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /**
     * Get all active templates
     */
    public List<ImageTemplate> getAllActiveTemplates() {
        return templateRepository.findByIsActiveTrueOrderByDisplayOrderAscCreatedAtDesc();
    }

    /**
     * Get templates by category
     */
    public List<ImageTemplate> getTemplatesByCategory(String category) {
        return templateRepository.findByCategoryAndIsActiveTrueOrderByDisplayOrderAsc(category);
    }

    /**
     * Get template by ID
     */
    public ImageTemplate getTemplateById(Long id) {
        return templateRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Template not found"));
    }

    /**
     * Create new template (Admin only)
     */
    @Transactional
    public ImageTemplate createTemplate(User createdBy, String templateName, String description,
                                        String category, Integer canvasWidth, Integer canvasHeight,
                                        String designJson, String tags) {
        logger.info("Creating new template: {}", templateName);

        ImageTemplate template = new ImageTemplate();
        template.setCreatedBy(createdBy);
        template.setTemplateName(templateName);
        template.setDescription(description);
        template.setCategory(category);
        template.setCanvasWidth(canvasWidth);
        template.setCanvasHeight(canvasHeight);
        template.setDesignJson(designJson);
        template.setTags(tags);
        template.setIsActive(true);
        template.setIsPremium(false);

        return templateRepository.save(template);
    }

    /**
     * Update template (Admin only)
     */
    @Transactional
    public ImageTemplate updateTemplate(Long id, String templateName, String description,
                                       String category, String designJson, String tags,
                                       Boolean isActive, Boolean isPremium, Integer displayOrder) {
        ImageTemplate template = getTemplateById(id);

        if (templateName != null) template.setTemplateName(templateName);
        if (description != null) template.setDescription(description);
        if (category != null) template.setCategory(category);
        if (designJson != null) template.setDesignJson(designJson);
        if (tags != null) template.setTags(tags);
        if (isActive != null) template.setIsActive(isActive);
        if (isPremium != null) template.setIsPremium(isPremium);
        if (displayOrder != null) template.setDisplayOrder(displayOrder);

        return templateRepository.save(template);
    }

    /**
     * Delete template (Admin only)
     */
    @Transactional
    public void deleteTemplate(Long id) {
        ImageTemplate template = getTemplateById(id);
        templateRepository.delete(template);
        logger.info("Template deleted: {}", id);
    }

    /**
     * Increment usage count
     */
    @Transactional
    public void incrementUsageCount(Long templateId) {
        ImageTemplate template = getTemplateById(templateId);
        template.setUsageCount(template.getUsageCount() + 1);
        templateRepository.save(template);
    }

    /**
     * Get all templates (including inactive) - Admin only
     */
    public List<ImageTemplate> getAllTemplates() {
        return templateRepository.findAll();
    }
}