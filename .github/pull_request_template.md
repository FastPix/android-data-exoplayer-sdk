# FastPix Resumable Uploads SDK - Documentation PR

## Documentation Changes

### What Changed
- [ ] New documentation added
- [ ] Existing documentation updated
- [ ] Documentation errors fixed
- [ ] Code examples updated
- [ ] Links and references updated

### Files Modified
- [ ] README.md
- [ ] docs/ files
- [ ] USAGE.md
- [ ] CONTRIBUTING.md
- [ ] Other: _______________

### Summary
**Brief description of changes:**

<!-- What documentation was added, updated, or fixed? -->

### Code Examples
```kotlin 
// If you added/updated code examples, include them here
private val fastPixDataSDK = FastPixBaseExoPlayer()
val playerDataDetails = PlayerDataDetails(
            "player-name",
            "player-version"
        )
        val videoDataDetails =
            VideoDataDetails(
                UUID.randomUUID().toString(),
                videoModel?.id
            ).apply {
                videoSeries = "This is video series"
                videoProducer = "This is video Producer"
                videoContentType = "This is video Content Type"
                videoVariant = "This is video Variant"
                videoLanguage = "This is video Language"
            }
        val customDataDetails = CustomDataDetails()
        customDataDetails.customField1 = "Custom 1"
        customDataDetails.customField2 = "Custom 2"
        fastPixDataSDK = FastPixBaseExoPlayer(
            this,
            playerView = binding.playerView,
            exoPlayer = exoPlayer,
            workSpaceId = "workspace-key",
            viewerId = UUID.randomUUID().toString(),
            videoDataDetails = videoDataDetails,
            playerDataDetails = playerDataDetails,
            customDataDetails = customDataDetails
        )
```

### Testing
- [ ] All code examples tested
- [ ] Links verified
- [ ] Grammar checked
- [ ] Formatting consistent

### Review Checklist
- [ ] Content is accurate
- [ ] Code examples work
- [ ] Links are working
- [ ] Grammar is correct
- [ ] Formatting is consistent

---

**Ready for review!**
